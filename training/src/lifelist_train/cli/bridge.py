"""Build `shared/model/taxon_bridge.json` — the crossing from iNaturalist ids to GBIF keys.

    lifelist-bridge --taxa-raw cache/taxa_raw.json.gz --joined cache/stage2_joined \
        --min-observations 20

Run rarely, and where the GBIF dump from stage 1 lives. The point of the artefact is that
**nothing downstream needs it** — stage 4 reads the bridge and works from embeddings alone.

Scientific names for the iNaturalist ids come from `--inat-taxa` if you still have their 39 MB
taxa table, and otherwise from the iNaturalist API, 30 ids at a time. Keeping a 39 MB table
around to look up three thousand names was never a good trade, and it is usually gone by the
time this runs.

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
from ._common import LOG, setup_logging, shared_model

GBIF_SPECIES = "https://api.gbif.org/v1/species"
INAT_TAXA = "https://api.inaturalist.org/v1/taxa"
INAT_BATCH = 30  # their ceiling for a multi-id lookup


def read_json(path: Path) -> Any:
    """stage 1 writes `.json`; it gets gzipped afterwards often enough to be worth handling."""
    if path.suffix == ".gz":
        import gzip

        with gzip.open(path, "rt", encoding="utf-8") as handle:
            return json.load(handle)
    return json.loads(path.read_text(encoding="utf-8"))


def names_from_table(path: Path, taxon_ids: list[int]) -> list[tuple[int, str, str]]:
    import pandas as pd

    table = pd.read_csv(
        path, sep="\t", usecols=["taxon_id", "rank", "name"], low_memory=False
    ).set_index("taxon_id")
    return [
        (t, str(table.at[t, "name"]), str(table.at[t, "rank"]))
        if t in table.index
        else (t, "", "")
        for t in taxon_ids
    ]


def names_from_api(taxon_ids: list[int]) -> list[tuple[int, str, str]]:
    """Scientific name and rank per iNaturalist id, from their API.

    A taxon that comes back without a name is returned empty rather than dropped: the bridge
    counts it as an unknown id, which is a number worth seeing rather than a row worth losing.
    """
    import time

    import requests

    session = requests.Session()
    found: dict[int, tuple[str, str]] = {}
    for start in range(0, len(taxon_ids), INAT_BATCH):
        batch = taxon_ids[start : start + INAT_BATCH]
        joined = ",".join(str(t) for t in batch)
        for attempt in range(4):
            try:
                response = session.get(f"{INAT_TAXA}/{joined}", timeout=30)
                response.raise_for_status()
                for result in response.json().get("results", []):
                    found[int(result["id"])] = (
                        str(result.get("name") or ""),
                        str(result.get("rank") or ""),
                    )
                break
            except Exception as exc:  # noqa: BLE001 — a retried batch is not a failed build
                LOG.debug("batch at %d attempt %d: %s", start, attempt, exc)
                time.sleep(2 * (attempt + 1))
        else:
            LOG.warning("gave up on ids %s", batch[:4])
        if start and start % (INAT_BATCH * 20) == 0:
            LOG.info("  %d of %d names", len(found), len(taxon_ids))
        time.sleep(0.4)  # their published courtesy limit is 60 requests a minute
    return [(t, *found.get(t, ("", ""))) for t in taxon_ids]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Bridge iNaturalist taxon ids to GBIF keys")
    parser.add_argument(
        "--taxa-raw", type=Path, required=True, help="stage 1's cache/taxa_raw.json[.gz]"
    )
    parser.add_argument(
        "--inat-taxa",
        type=Path,
        default=None,
        help="iNaturalist taxa.csv.gz, if you have it. Without it, names come from their API.",
    )
    parser.add_argument("--joined", type=Path, required=True, help="cache/stage2_joined")
    parser.add_argument("--min-observations", type=int, default=20)
    parser.add_argument("--max-photos-per-taxon", type=int, default=150)
    parser.add_argument("--out", type=Path, default=shared_model("taxon_bridge.json"))
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

    raw = read_json(args.taxa_raw)
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

    taxon_ids = sorted(int(t) for t in selected)
    if args.inat_taxa:
        rows = names_from_table(args.inat_taxa, taxon_ids)
    else:
        LOG.info("looking up %d scientific names from the iNaturalist API", len(taxon_ids))
        rows = names_from_api(taxon_ids)

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
