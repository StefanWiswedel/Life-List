"""Stage 3 — embed the stage 2 photo manifest with BioCLIP.

    lifelist-embed --cache-dir cache --shard-size 4000

Resumable by design: every completed shard is a file on disk, and re-running skips it.
Kill the process, close the laptop, lose the Colab runtime — you lose at most one shard.

The heavy dependencies are optional extras (`pip install -e '.[torch]'`) and are imported
inside the functions that need them, so `--dry-run` and the whole of `embed.py` stay
importable on a machine with no torch. That is what lets stage 3 be tested in CI.
"""

from __future__ import annotations

import argparse
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any

import numpy as np
import pandas as pd

from ..embed import (
    embed_shard,
    load_shards,
    plan_remaining,
    shard_failures,
    write_shard,
)
from ..inat import photo_url
from ._common import LOG, add_common_args, cache_path, setup_logging

DEFAULT_MODEL = "hf-hub:imageomics/bioclip"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Embed the photo manifest with BioCLIP")
    parser.add_argument(
        "--shard-size",
        type=int,
        default=4000,
        help="photos per checkpoint. Smaller loses less to an interruption, costs more files.",
    )
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument(
        "--download-workers",
        type=int,
        default=16,
        help="concurrent S3 fetches. The network is the bottleneck, not the model.",
    )
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument(
        "--device",
        default="auto",
        help="auto | cpu | cuda. auto picks cuda when torch can see it.",
    )
    parser.add_argument(
        "--max-shards",
        type=int,
        default=None,
        help="stop after this many shards this run — useful against a Colab time limit",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="report what remains and exit, without torch or network",
    )
    parser.add_argument(
        "--retry-failures",
        action="store_true",
        help="re-attempt photos previously recorded as failed (default: leave them be)",
    )
    return add_common_args(parser)


def resolve_device(requested: str) -> str:
    if requested != "auto":
        return requested
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except ImportError:
        return "cpu"


def make_fetcher(workers: int) -> Any:
    """A session-backed fetcher. One connection pool, reused across every shard."""
    import requests
    from requests.adapters import HTTPAdapter

    session = requests.Session()
    adapter = HTTPAdapter(pool_connections=workers, pool_maxsize=workers)
    session.mount("https://", adapter)

    def fetch(photo_id: int, extension: str) -> bytes:
        response = session.get(photo_url(photo_id, extension), timeout=30)
        response.raise_for_status()
        return response.content

    return fetch


def prefetch(fetch: Any, rows: pd.DataFrame, workers: int) -> dict[int, bytes | Exception]:
    """Download a shard's photos concurrently, then hand them to the model in order.

    Downloading and encoding are interleaved only at shard granularity on purpose: a
    GPU that stalls waiting for one slow S3 object is a GPU running at a fraction of
    its rate, and shards are small enough that holding one in memory is cheap
    (4000 photos at ~100 KB is ~400 MB).
    """
    out: dict[int, bytes | Exception] = {}

    def one(item: tuple[int, str]) -> None:
        photo_id, extension = item
        try:
            out[photo_id] = fetch(photo_id, extension)
        except Exception as exc:  # noqa: BLE001 — recorded per photo, never fatal
            out[photo_id] = exc

    items = [(int(r.photo_id), str(r.extension)) for r in rows.itertuples(index=False)]
    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(one, items))
    return out


def make_encoder(model_name: str, device: str) -> Any:
    """Load BioCLIP once and return a bytes -> embedding matrix callable."""
    import io

    import open_clip
    import torch
    from PIL import Image

    LOG.info("loading %s on %s", model_name, device)
    model, _, preprocess = open_clip.create_model_and_transforms(model_name)
    model = model.to(device).eval()

    @torch.no_grad()
    def encode(batch: list[bytes]) -> np.ndarray:
        images = [
            preprocess(Image.open(io.BytesIO(raw)).convert("RGB")) for raw in batch
        ]
        tensor = torch.stack(images).to(device)
        features = model.encode_image(tensor)
        return features.detach().cpu().float().numpy()

    return encode


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    manifest_path = cache_path(args.cache_dir, "photo_manifest.parquet")
    if not manifest_path.exists():
        LOG.error(
            "%s not found. Stage 2 writes it — run `lifelist-images ... --commit` first.",
            manifest_path,
        )
        return 1

    manifest = pd.read_parquet(manifest_path)
    out_dir = cache_path(args.cache_dir, "embeddings")
    out_dir.mkdir(parents=True, exist_ok=True)

    remaining = plan_remaining(
        manifest, args.shard_size, out_dir, retry_failures=args.retry_failures
    )
    outstanding = sum(len(s) for s in remaining)
    LOG.info(
        "%d photos, %d taxa — %d already resolved, %d to go in %d shards of %d",
        len(manifest),
        manifest["taxon_id"].nunique(),
        len(manifest) - outstanding,
        outstanding,
        len(remaining),
        args.shard_size,
    )

    if args.dry_run:
        failures = shard_failures(out_dir)
        if len(failures):
            LOG.info("%d photos failed so far (--retry-failures to re-attempt)", len(failures))
        return 0

    if not remaining:
        embedded = load_shards(out_dir)
        LOG.info("nothing to do: %d embeddings on disk", len(embedded))
        return 0

    if args.max_shards is not None:
        remaining = remaining[: args.max_shards]

    device = resolve_device(args.device)
    fetch = make_fetcher(args.download_workers)
    encode = make_encoder(args.model, device)

    started = time.time()
    for position, shard in enumerate(remaining, start=1):
        shard_started = time.time()
        cached = prefetch(fetch, shard.rows, args.download_workers)

        def from_cache(photo_id: int, _extension: str, _cached: dict = cached) -> bytes:
            value = _cached[photo_id]
            if isinstance(value, Exception):
                raise value
            return value

        embeddings, kept, failures = embed_shard(
            shard, from_cache, encode, batch_size=args.batch_size
        )
        write_shard(out_dir, shard, embeddings, kept, failures)

        elapsed = time.time() - shard_started
        done = position
        rate = (time.time() - started) / done
        LOG.info(
            "%s: %d embedded, %d failed, %.0f s (%.0f photos/s) — %d/%d this run, ~%.0f min left",
            shard.name,
            len(kept),
            len(failures),
            elapsed,
            len(kept) / elapsed if elapsed else 0.0,
            done,
            len(remaining),
            (len(remaining) - done) * rate / 60,
        )

    total = load_shards(out_dir)
    failures = shard_failures(out_dir)
    still_to_do = sum(len(s) for s in plan_remaining(manifest, args.shard_size, out_dir))
    LOG.info(
        "%d embeddings, %d failures, %d photos still to go",
        len(total),
        len(failures),
        still_to_do,
    )
    if still_to_do:
        LOG.info("re-run the same command to continue")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
