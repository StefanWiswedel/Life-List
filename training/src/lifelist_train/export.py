"""Stage 6 — export the backbone and head to ONNX, and prove the export did not lie.

The failure this guards against is silent. A quantised model that is 2% worse looks
exactly like a model that is fine, right up until the app is worse than the notebook and
nobody can say when it started. So export is not "write the file"; it is write the file
and then assert that it still computes what torch computed.

Everything here that decides anything — comparing two sets of embeddings, deciding whether
a drift is acceptable — is numpy over arrays and testable without torch or onnxruntime.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np

# Cosine similarity below this between torch and ONNX embeddings means the export changed
# the model. 0.999 is deliberately tight for fp32 and deliberately loose for int8, which is
# why the two are checked separately.
FP32_MIN_COSINE = 0.9999
INT8_MIN_COSINE = 0.99


@dataclass(frozen=True, slots=True)
class ParityReport:
    n: int
    mean_cosine: float
    min_cosine: float
    top1_agreement: float

    def ok(self, minimum: float) -> bool:
        return self.min_cosine >= minimum

    def summary(self) -> str:
        return (
            f"n={self.n}  cosine mean={self.mean_cosine:.5f} min={self.min_cosine:.5f}  "
            f"top-1 agreement={self.top1_agreement:.1%}"
        )


def cosine_rows(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Row-wise cosine similarity between two equal-shaped matrices."""
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    if a.shape != b.shape:
        raise ValueError(f"shape mismatch: {a.shape} vs {b.shape}")
    na = np.linalg.norm(a, axis=1)
    nb = np.linalg.norm(b, axis=1)
    denominator = np.where((na * nb) == 0, 1.0, na * nb)
    return (a * b).sum(axis=1) / denominator


def compare_embeddings(
    reference: np.ndarray, exported: np.ndarray, head_weight: np.ndarray | None = None,
    head_bias: np.ndarray | None = None,
) -> ParityReport:
    """How far the exported model drifted from the reference.

    Cosine on the embeddings, and — when the head is supplied — whether the two still
    pick the same class. The second matters more: an embedding can move a long way in a
    direction the head does not care about, and it can move very little in one it does.
    """
    cosines = cosine_rows(reference, exported)

    agreement = float("nan")
    if head_weight is not None and head_bias is not None:
        a = reference @ head_weight.T + head_bias
        b = exported @ head_weight.T + head_bias
        agreement = float((a.argmax(axis=1) == b.argmax(axis=1)).mean())

    return ParityReport(
        n=len(cosines),
        mean_cosine=float(cosines.mean()),
        min_cosine=float(cosines.min()),
        top1_agreement=agreement,
    )


def export_backbone(model_name: str, out_path: Path, opset: int = 18) -> Path:
    """Trace BioCLIP's image tower to ONNX at the spec's 224x224 input.

    Opset 18, not 17, because Resize gained `antialias` in 18 and the preprocessing graph
    needs it: PIL antialiases when downscaling, and a resize that does not changes 8% of
    predictions (VERIFICATION.md 22). The opset is therefore load-bearing, not incidental.
    """
    import open_clip
    import torch

    model, _, _ = open_clip.create_model_and_transforms(model_name)
    model = model.eval()

    class ImageTower(torch.nn.Module):
        def __init__(self, clip):
            super().__init__()
            self.clip = clip

        def forward(self, pixel_values):
            return self.clip.encode_image(pixel_values)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    # Traced at batch 2, not 1. At batch 1 the tracer sees a leading dimension of size one
    # and folds it into the attention reshapes, producing a graph that runs at batch 1 and
    # throws at batch 5 — which is precisely the size the app uses for multi-photo fusion,
    # so the failure would appear only on the feature that justifies the export.
    dummy = torch.zeros(2, 3, 224, 224)
    torch.onnx.export(
        ImageTower(model),
        dummy,
        str(out_path),
        input_names=["pixel_values"],
        output_names=["embedding"],
        # Batch is dynamic because the app embeds up to five photos of one observation in
        # a single pass (§3.1 fusion, max_images 5).
        dynamic_axes={"pixel_values": {0: "batch"}, "embedding": {0: "batch"}},
        opset_version=opset,
        do_constant_folding=True,
    )
    return out_path


def attach_head(
    backbone: Path, weight: np.ndarray, bias: np.ndarray, dst: Path
) -> Path:
    """Append L2-normalisation and the linear head to the backbone graph.

    One session on device instead of two, and — more importantly — one place where the
    normalisation happens. The head was trained on unit vectors (`embed.l2_normalise`);
    a Kotlin caller that forgot to normalise would get plausible, wrong logits with
    nothing to indicate it. Folding it into the graph makes that unforgettable.

    The exported model takes pixels and returns logits. Temperature and softmax stay
    outside, in Kotlin, because the threshold is adjustable at display time (§4.4) and
    baking a temperature into the graph would freeze it at export.
    """
    import onnx
    from onnx import TensorProto, helper, numpy_helper

    model = onnx.load(str(backbone))
    graph = model.graph
    embedding = graph.output[0].name

    initialisers = [
        numpy_helper.from_array(np.asarray(weight, dtype=np.float32).T, "head_weight"),
        numpy_helper.from_array(np.asarray(bias, dtype=np.float32), "head_bias"),
    ]
    nodes = [
        helper.make_node("LpNormalization", [embedding], ["embedding_unit"], axis=1, p=2),
        helper.make_node("MatMul", ["embedding_unit", "head_weight"], ["head_matmul"]),
        helper.make_node("Add", ["head_matmul", "head_bias"], ["logits"]),
    ]
    graph.initializer.extend(initialisers)
    graph.node.extend(nodes)

    del graph.output[:]
    graph.output.extend(
        [helper.make_tensor_value_info("logits", TensorProto.FLOAT, ["batch", len(bias)])]
    )

    onnx.checker.check_model(model, full_check=False)
    dst.parent.mkdir(parents=True, exist_ok=True)
    # Single file: Android asset loading with a sidecar .data file is an avoidable class
    # of bug, and 329 MB is well inside protobuf's 2 GB ceiling.
    onnx.save(model, str(dst))
    return dst


def quantise(src: Path, dst: Path) -> Path:
    """Dynamic int8 quantisation.

    Dynamic rather than static: static needs a calibration set, and a calibration set
    drawn from the same photos the head trained on would flatter the result. Dynamic
    quantises weights only and leaves activations in float, which is the honest default
    for a transformer on CPU.
    """
    from onnxruntime.quantization import QuantType, quantize_dynamic

    dst.parent.mkdir(parents=True, exist_ok=True)
    quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8)
    return dst


def run_onnx(path: Path, pixel_values: np.ndarray) -> np.ndarray:
    import onnxruntime

    session = onnxruntime.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    name = session.get_inputs()[0].name
    return session.run(None, {name: pixel_values.astype(np.float32)})[0]


# Preprocessing constants — spec §5, verified against the loaded checkpoint (VERIFICATION.md §13).
PIXEL_MEAN = (0.48145466, 0.4578275, 0.40821073)
PIXEL_STD = (0.26862954, 0.26130258, 0.27577711)
INPUT_SIZE = 224


def with_preprocessing(src: Path, dst: Path) -> Path:
    """Prepend resize and normalisation so the client never reimplements them.

    A bilinear resize where training used antialiased bicubic changes 8% of predictions
    (VERIFICATION.md §22) — a bigger effect than int8 quantisation, and invisible. The only
    reliable fix is to stop the client from having an opinion: the shipped graph takes raw
    uint8 NHWC pixels and the client's sole obligation is a centre crop to a square, which
    is integer arithmetic.

    Requires opset 18: `antialias` does not exist on Resize before it.
    """
    import onnx
    from onnx import TensorProto, helper, numpy_helper

    model = onnx.load(str(src))
    graph = model.graph
    tensor_input = graph.input[0].name

    mean = np.array(PIXEL_MEAN, dtype=np.float32).reshape(1, 3, 1, 1)
    std = np.array(PIXEL_STD, dtype=np.float32).reshape(1, 3, 1, 1)

    initialisers = [
        numpy_helper.from_array(np.array([255.0], dtype=np.float32), "pp_255"),
        numpy_helper.from_array(mean, "pp_mean"),
        numpy_helper.from_array(std, "pp_std"),
        numpy_helper.from_array(np.array([], dtype=np.float32), "pp_roi"),
        numpy_helper.from_array(np.array([], dtype=np.float32), "pp_scales"),
        numpy_helper.from_array(np.array([0], dtype=np.int64), "pp_zero"),
        numpy_helper.from_array(np.array([1], dtype=np.int64), "pp_one"),
        numpy_helper.from_array(
            np.array([3, INPUT_SIZE, INPUT_SIZE], dtype=np.int64), "pp_chw"
        ),
    ]
    nodes = [
        helper.make_node("Cast", ["image_uint8"], ["pp_f"], to=TensorProto.FLOAT, name="pp_cast"),
        helper.make_node("Transpose", ["pp_f"], ["pp_nchw"], perm=[0, 3, 1, 2], name="pp_t"),
        helper.make_node("Shape", ["pp_nchw"], ["pp_shape"], name="pp_shape"),
        helper.make_node(
            "Slice", ["pp_shape", "pp_zero", "pp_one", "pp_zero"], ["pp_batch"], name="pp_slice"
        ),
        helper.make_node("Concat", ["pp_batch", "pp_chw"], ["pp_sizes"], axis=0, name="pp_cat"),
        helper.make_node(
            "Resize",
            ["pp_nchw", "pp_roi", "pp_scales", "pp_sizes"],
            ["pp_resized"],
            mode="cubic",
            coordinate_transformation_mode="half_pixel",
            cubic_coeff_a=-0.5,
            antialias=1,
            nearest_mode="floor",
            name="pp_resize",
        ),
        helper.make_node("Div", ["pp_resized", "pp_255"], ["pp_scaled"], name="pp_div"),
        helper.make_node("Sub", ["pp_scaled", "pp_mean"], ["pp_centred"], name="pp_sub"),
        helper.make_node("Div", ["pp_centred", "pp_std"], [tensor_input], name="pp_norm"),
    ]

    existing = list(graph.node)
    del graph.node[:]
    graph.node.extend(nodes + existing)  # ONNX requires topological order
    graph.initializer.extend(initialisers)
    del graph.input[:]
    graph.input.extend(
        [
            helper.make_tensor_value_info(
                "image_uint8", TensorProto.UINT8, ["batch", "height", "width", 3]
            )
        ]
    )

    onnx.checker.check_model(model, full_check=False)
    dst.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(dst))
    return dst
