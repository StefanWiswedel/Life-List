"""Build `shared/model/reference_photos.json` — which photograph represents each taxon.

    lifelist-reference-index --out shared/model/reference_photos.json -v

The index is a committed artefact that, until now, no committed code produced. It was written
once by a throwaway script and then trusted for weeks — the same shape of mistake as the
taxonomy that shipped with every vernacular null (VERIFICATION.md §28). This is that script,
written down.

What it does: ask iNaturalist for each taxon's curated photograph list and take the first one
we are licensed to redistribute. See `reference.py` for why that beats anything cleverer.

The GBIF↔iNaturalist mapping is the awkward part. The two id spaces are unrelated, and the
first build had to recover it by joining the existing index against the 334k-row photo manifest
on `photo_id`. That join is supported here with `--manifest`, but the mapping is written into
the index afterwards, so every later rebuild reads it straight back.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

from ..reference import build_index, summarise
from ._common import LOG, setup_logging

API = "https://api.inaturalist.org/v1/taxa"
USER_AGENT = "LifeList/0.8 (https://github.com/StefanWiswedel/Life-List)"

#: iNaturalist accepts up to 30 comma-separated ids per request and asks for ~1 request/second.
BATCH = 30
PAUSE = 1.0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Choose a reference photo for every taxon")
    parser.add_argument("--out", type=Path, default=Path("shared/model/reference_photos.json"))
    parser.add_argument(
        "--previous",
        type=Path,
        default=Path("shared/model/reference_photos.json"),
        help="the current index: supplies the fallback photo and, after the first run, the "
             "GBIF->iNaturalist mapping",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=None,
        help="cache/photo_manifest.parquet — only needed the first time, to recover the "
             "GBIF->iNaturalist mapping by joining on photo_id",
    )
    parser.add_argument("--pause", type=float, default=PAUSE)
    parser.add_argument("--batch-size", type=int, default=BATCH)
    parser.add_argument("--limit", type=int, default=None, help="stop after N taxa, for testing")
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def load_pairs(previous: list[dict[str, Any]], manifest: Path | None) -> list[tuple[int, int]]:
    """(gbif_id, inat_id) for every taxon we ship a photo for."""
    known = [
        (int(e["taxon_id"]), int(e["inat_taxon_id"]))
        for e in previous
        if e.get("inat_taxon_id")
    ]
    if known:
        LOG.info("read %d GBIF->iNaturalist pairs from the existing index", len(known))
        return known

    if manifest is None or not manifest.exists():
        raise SystemExit(
            "no inat_taxon_id in the index and no --manifest to recover it from. "
            "Pass --manifest cache/photo_manifest.parquet for the first build."
        )

    import pandas as pd

    LOG.info("recovering the mapping by joining %s on photo_id", manifest)
    rows = pd.read_parquet(manifest, columns=["taxon_id", "photo_id"])
    photo_to_inat = dict(
        zip(rows["photo_id"].astype("int64"), rows["taxon_id"].astype("int64"), strict=True)
    )
    recovered: list[tuple[int, int]] = []
    for entry in previous:
        inat = photo_to_inat.get(int(entry["photo_id"]))
        if inat is not None:
            recovered.append((int(entry["taxon_id"]), int(inat)))
    LOG.info("recovered %d of %d", len(recovered), len(previous))
    return recovered


def fetch(session: Any, ids: list[int]) -> dict[int, dict[str, Any]]:
    """One batch of taxa, with their curated photo lists."""
    url = f"{API}/{','.join(str(i) for i in ids)}"
    for attempt in range(6):
        try:
            response = session.get(url, headers={"User-Agent": USER_AGENT}, timeout=60)
            if response.status_code == 429:
                wait = min(float(response.headers.get("Retry-After") or 20), 60)
                LOG.debug("throttled, waiting %.0f s", wait)
                time.sleep(wait)
                continue
            response.raise_for_status()
            return {int(t["id"]): t for t in response.json().get("results", [])}
        except Exception as exc:  # noqa: BLE001 — one bad batch must not end the run
            LOG.debug("attempt %d failed: %s", attempt, exc)
            time.sleep(min(5 * (attempt + 1), 60))
    LOG.warning("gave up on a batch of %d taxa; they keep their existing photo", len(ids))
    return {}


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    if not args.previous.exists():
        LOG.error("%s not found", args.previous)
        return 1
    previous = json.loads(args.previous.read_text(encoding="utf-8"))
    fallbacks = {int(e["taxon_id"]): e for e in previous}

    pairs = load_pairs(previous, args.manifest)
    if args.limit:
        pairs = pairs[: args.limit]

    import requests

    session = requests.Session()
    taxa: dict[int, dict[str, Any]] = {}
    inat_ids = [inat for _, inat in pairs]

    for start in range(0, len(inat_ids), args.batch_size):
        batch = inat_ids[start : start + args.batch_size]
        taxa.update(fetch(session, batch))
        LOG.info("%d/%d taxa fetched", min(start + len(batch), len(inat_ids)), len(inat_ids))
        time.sleep(args.pause)

    index = build_index(pairs, taxa, fallbacks)
    counts = summarise(index)
    LOG.info(
        "%d taxa: %d from iNaturalist's curated list, %d keeping the training-set photo",
        counts["taxa"], counts["curated"], counts["fallback"],
    )

    args.out.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.out.with_suffix(args.out.suffix + ".tmp")
    temporary.write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    temporary.replace(args.out)
    LOG.info("wrote %s", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
