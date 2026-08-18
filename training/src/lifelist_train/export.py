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


def export_backbone(model_name: str, out_path: Path, opset: int = 17) -> Path:
    """Trace BioCLIP's image tower to ONNX at the spec's 224×224 input."""
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
