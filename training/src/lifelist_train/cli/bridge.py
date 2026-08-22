"""Build `shared/model/taxon_bridge.json` — the crossing from iNaturalist ids to GBIF keys.

    lifelist-bridge --taxa-raw cache/taxa_raw.json --inat-taxa inat/taxa.csv.gz \
        --joined cache/stage2_joined --min-observations 20

Run rarely, and only where the two big inputs live: the 31 MB GBIF dump from stage 1 and the
39 MB iNaturalist taxa table. The point of the artefact is that **nothing downstream needs
either of them** — stage 4 reads the bridge and works from embeddings alone.

Build it at the *lowest* threshold you might ever want, because narrowing is free
(`bridge.restrict`) and widening means running this again.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from ..bridge import build_mapping, document, genus_keys_by_name, needed_keys
from ..inat import coverage, select_taxa
from ..names import build_synonym_index
from ._common import LOG, setup_logging

GBIF_SPECIES = "https://api.gbif.org/v1/species"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Bridge iNaturalist taxon ids to GBIF keys")
    parser.add_argument(
        "--taxa-raw", type=Path, required=True, help="stage 1's cache/taxa_raw.json"
    )
    parser.add_argument("--inat-taxa", type=Path, required=True, help="iNaturalist taxa.csv.gz")
    parser.add_argument("--joined", type=Path, required=True, help="cache/stage2_joined")
    parser.add_argument("--min-observations", type=int, default=20)
    parser.add_argument("--max-photos-per-taxon", type=int, default=150)
    parser.add_argument("--out", type=Path, default=Path("shared/model/taxon_bridge.json"))
    parser.add_argument(
        "--no-fetch",
        action="store_true",
        help="skip asking GBIF for records stage 1 never fetched (genera, mostly)",
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def read_joined(path: Path) -> Any:
    import pandas as pd

    if path.is_dir():
        parts = sorted(path.glob("*.parquet"))
        if not parts:
            raise FileNotFoundError(f"no parquet parts in {path}")
        return pd.concat((pd.read_parquet(p) for p in parts), ignore_index=True)
    return pd.read_parquet(path)


def fetch_records(keys: list[int]) -> dict[int, dict[str, Any]]:
    """The handful of GBIF records stage 1 never fetched.

    Almost always genera: a genus is present in the dump as somebody's ancestor but has no
    record of its own, and a genus with an indeterminate leaf needs one.
    """
    import time

    import requests

    from ..gbif import parse_backbone_record

    session = requests.Session()
    out: dict[int, dict[str, Any]] = {}
    for key in keys:
        for attempt in range(4):
            try:
                response = session.get(f"{GBIF_SPECIES}/{key}", timeout=30)
                response.raise_for_status()
                taxon = parse_backbone_record(response.json())
                if taxon is not None:
                    out[key] = {
                        "scientific_name": taxon.scientific_name,
                        "rank": taxon.rank,
                        "status": taxon.status,
                        "lineage": taxon.lineage,
                        "lineage_names": taxon.lineage_names,
                        "vernacular_en": None,
                        "vernacular_da": None,
                    }
                break
            except Exception as exc:  # noqa: BLE001 — one missing genus is not a failed build
                LOG.debug("key %s attempt %d: %s", key, attempt, exc)
                time.sleep(2)
        time.sleep(0.15)
    return out


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    import pandas as pd

    raw = json.loads(args.taxa_raw.read_text(encoding="utf-8"))
    accepted = {t["key"]: t["scientific_name"] for t in raw["taxa"]}
    doubtful = frozenset(
        t["key"] for t in raw["taxa"] if str(t.get("status", "")).upper() == "DOUBTFUL"
    )
    index = build_synonym_index(
        accepted,
        [(s["name"], s["accepted_key"]) for s in raw["synonyms"]],
        doubtful,
    )
    records: dict[int, dict[str, Any]] = {int(t["key"]): t for t in raw["taxa"]}
    genus_keys = genus_keys_by_name(raw["taxa"])
    LOG.info(
        "%d GBIF taxa, %d synonyms, %d genus names",
        len(records), len(raw["synonyms"]), len(genus_keys),
    )

    joined = read_joined(args.joined)
    selected = select_taxa(coverage(joined), args.min_observations, args.max_photos_per_taxon)
    LOG.info("%d iNaturalist taxa at >=%d observations", len(selected), args.min_observations)

    names = pd.read_csv(
        args.inat_taxa, sep="\t", usecols=["taxon_id", "rank", "name"], low_memory=False
    ).set_index("taxon_id")

    rows = []
    for taxon_id in selected:
        taxon_id = int(taxon_id)
        if taxon_id in names.index:
            rows.append(
                (taxon_id, str(names.at[taxon_id, "name"]), str(names.at[taxon_id, "rank"]))
            )
        else:
            rows.append((taxon_id, "", ""))

    mapping, parents, report = build_mapping(rows, index, genus_keys)
    LOG.info("%s", report.summary())

    missing = sorted(needed_keys(mapping, parents) - set(records))
    if missing and not args.no_fetch:
        LOG.info("fetching %d GBIF records stage 1 never had (mostly genera)", len(missing))
        records.update(fetch_records(missing))
    still = sorted(needed_keys(mapping, parents) - set(records))
    if still:
        LOG.warning("%d keys still have no record and will be dropped: %s", len(still), still[:8])

    doc = document(mapping, parents, records)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.out.with_suffix(args.out.suffix + ".tmp")
    temporary.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    temporary.replace(args.out)
    LOG.info(
        "wrote %s — %d iNaturalist taxa, %d GBIF records, %d genera with an indeterminate leaf",
        args.out, len(doc["mapping"]), len(doc["taxa"]), len(doc["indeterminate_parents"]),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
