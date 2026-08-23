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
on `photo_id`. That join is still supported with `--manifest`, and the mapping is written into
the index, so a rebuild reads it straight back.

**Reading it back is not enough when the model grows**, and that was a bug worth catching before
it shipped: the pairs came only from the existing index, so a rebuild after the ≥20 retrain
would have re-fetched the same 2,294 taxa and produced an index still covering 2,294 — every one
of the 1,188 new species identifying correctly and then showing a blank where the comparison
photograph goes. `--bridge` is the fix and is now the default source: `taxon_bridge.json`
already holds the crossing for every taxon in the model (§42), which is exactly what this needs.
The taxonomy decides which of them are leaves worth a photograph.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

from ..reference import build_index, summarise
from ._common import LOG, setup_logging, shared_model

API = "https://api.inaturalist.org/v1/taxa"
USER_AGENT = "LifeList/0.8 (https://github.com/StefanWiswedel/Life-List)"

#: iNaturalist accepts up to 30 comma-separated ids per request and asks for ~1 request/second.
BATCH = 30
PAUSE = 1.0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Choose a reference photo for every taxon")
    parser.add_argument("--out", type=Path, default=shared_model("reference_photos.json"))
    parser.add_argument(
        "--previous",
        type=Path,
        default=shared_model("reference_photos.json"),
        help="the current index: supplies the fallback photo and, after the first run, the "
             "GBIF->iNaturalist mapping",
    )
    parser.add_argument(
        "--bridge",
        type=Path,
        default=shared_model("taxon_bridge.json"),
        help="taxon_bridge.json — the GBIF<->iNaturalist crossing for every taxon in the model",
    )
    parser.add_argument(
        "--taxonomy",
        type=Path,
        default=shared_model("taxonomy.json"),
        help="which taxa are leaves, and so want a photograph",
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


def pairs_from_bridge(bridge: Path, taxonomy: Path) -> list[tuple[int, int]]:
    """(gbif_id, inat_id) for every leaf in the shipped taxonomy.

    Signed, not absolute: an indeterminate leaf is `-1036775`, the same negative id the
    taxonomy and the app use, and its photograph is the genus's. Taking `abs` here would file
    "some *Carabus*" under the *Carabus* genus node, which is not a leaf and is not what the
    result screen looks up.
    """
    mapping = {
        int(inat): int(gbif)
        for inat, gbif in json.loads(bridge.read_text(encoding="utf-8"))["mapping"].items()
    }
    leaves = {
        int(node["taxon_id"])
        for node in json.loads(taxonomy.read_text(encoding="utf-8"))
        if node.get("leaf_index") is not None
    }
    pairs = sorted(
        ((gbif, inat) for inat, gbif in mapping.items() if gbif in leaves),
        key=lambda pair: pair[0],
    )
    LOG.info("%d of %d leaves have an iNaturalist taxon behind them", len(pairs), len(leaves))
    return pairs


def load_pairs(previous: list[dict[str, Any]], manifest: Path | None) -> list[tuple[int, int]]:
    """(gbif_id, inat_id) for every taxon already in the index. Superseded by the bridge."""
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

    if args.bridge.exists() and args.taxonomy.exists():
        pairs = pairs_from_bridge(args.bridge, args.taxonomy)
    else:
        LOG.warning(
            "no %s — falling back to the taxa already in the index, which cannot grow",
            args.bridge,
        )
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
