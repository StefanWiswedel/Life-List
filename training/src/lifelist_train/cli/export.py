"""Stage 6 CLI — build the shipped model from committed weights.

    lifelist-export --head ../shared/model/head.npz --out app/src/main/assets/lifelist.onnx

The 350 MB artefact is never committed. What is committed is the 4 MB head and the
taxonomy, and this command rebuilds the rest from a pinned public checkpoint — so the
model in a release is reproducible from source rather than a binary someone once made
and nobody can regenerate.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np

from ..export import attach_head, export_backbone, with_preprocessing
from ._common import LOG, setup_logging

DEFAULT_MODEL = "hf-hub:imageomics/bioclip"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Export the shipped ONNX model")
    parser.add_argument("--head", type=Path, required=True, help="head.npz from stage 4")
    parser.add_argument("--out", type=Path, required=True, help="where to write the model")
    parser.add_argument("--backbone", default=DEFAULT_MODEL)
    parser.add_argument(
        "--work-dir", type=Path, default=Path("build/export"), help="intermediates land here"
    )
    parser.add_argument(
        "--meta", type=Path, default=None, help="model_meta.json to stamp with the real n_taxa"
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    head = np.load(args.head)
    weight, bias = head["weight"], head["bias"]
    LOG.info("head: %d taxa x %d dims", weight.shape[0], weight.shape[1])

    args.work_dir.mkdir(parents=True, exist_ok=True)
    started = time.time()

    backbone = export_backbone(args.backbone, args.work_dir / "backbone.onnx")
    LOG.info("backbone exported (%.0f s)", time.time() - started)

    with_head = attach_head(backbone, weight, bias, args.work_dir / "with_head.onnx")
    LOG.info("head attached")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with_preprocessing(with_head, args.out)
    size_mb = args.out.stat().st_size / 1e6
    LOG.info("wrote %s — %.1f MB in %.0f s", args.out, size_mb, time.time() - started)

    if args.meta is not None and args.meta.exists():
        meta = json.loads(args.meta.read_text(encoding="utf-8"))
        meta["n_taxa"] = int(weight.shape[0])
        meta["embedding_dim"] = int(weight.shape[1])
        args.meta.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
        LOG.info("stamped %s with n_taxa=%d", args.meta, meta["n_taxa"])

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
