"""Build Denmark's species checklist — VERIFICATION §81.

    lifelist-checklist -v
    lifelist-checklist --min-records 5 --cache-dir cache

Three passes over GBIF: the occurrence facets for every species key recorded in the country
with its count (about ninety seconds for fifty thousand), the backbone record for each key above
the threshold (about five minutes for thirty thousand at twenty-four workers), and the vernacular
names (about eight minutes, and only two in five species have an English one).

Cached per pass and resumable, because the middle two are tens of thousands of requests and a
proxy that restarts halfway through must not cost the whole run — which it did once already,
at batch 245 of 374 (§70).
"""

from __future__ import annotations

import argparse
import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import date
from pathlib import Path

from ..checklist import Species, document, families, family_key, wanted
from ..gbif import GbifClient, parse_backbone_record, pick_vernacular
from ._common import LOG, cache_path, setup_logging, shared_model, write_json


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--country", default="DK")
    parser.add_argument(
        "--min-records",
        type=int,
        default=5,
        help=(
            "how many independent Danish records a species needs to count as occurring here. "
            "5 gives 29,273 species; 1 gives 52,487, most of the tail being single records "
            "and taxonomic noise; 50 gives 12,505 and starts losing real ones."
        ),
    )
    parser.add_argument("--taxonomy", type=Path, default=shared_model("taxonomy.json"))
    parser.add_argument("--out", type=Path, default=shared_model("checklist.json"))
    parser.add_argument("--cache-dir", type=Path, default=Path("cache"))
    parser.add_argument("--workers", type=int, default=24)
    parser.add_argument("--no-vernaculars", action="store_true")
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def identifiable(taxonomy: Path) -> set[int]:
    """The species the bundled model can name. Synthetic `sp.` leaves are not species (§68)."""
    if not taxonomy.exists():
        LOG.warning("no %s — every species will be marked as not identifiable", taxonomy)
        return set()
    nodes = json.loads(taxonomy.read_text(encoding="utf-8"))
    return {
        int(n["taxon_id"])
        for n in nodes
        if n.get("leaf_index") is not None and int(n["taxon_id"]) > 0
    }


def cached_lines(path: Path) -> dict[int, dict]:
    """Whatever a previous run got through, keyed by species key.

    **A failure is not cached.** The first version kept error rows like any other, so a key
    that hit a transient `ConnectionError` was never asked again — and one of the six that did
    was the Northern Raven, on 438,000 Danish records. A resumable fetch that remembers its
    failures turns a dropped packet into a permanent hole in the checklist, and the hole is
    invisible: the species is simply not there to miss.
    """
    if not path.exists():
        return {}
    out: dict[int, dict] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if "error" in row:
            continue
        out[int(row["key"])] = row
    return out


def fetch(path: Path, keys: list[int], work, workers: int, label: str) -> dict[int, dict]:
    """Run `work` over the keys that are not cached yet, appending as it goes."""
    have = cached_lines(path)
    todo = [key for key in keys if key not in have]
    if not todo:
        LOG.info("%s: all %d cached", label, len(have))
        return have
    LOG.info("%s: %d cached, %d to fetch", label, len(have), len(todo))
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as sink, ThreadPoolExecutor(workers) as pool:
        for position, row in enumerate(pool.map(work, todo), start=1):
            sink.write(json.dumps(row, ensure_ascii=False) + "\n")
            have[int(row["key"])] = row
            if position % 5000 == 0:
                LOG.info("  %s %d/%d", label, position, len(todo))
    return have


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)
    client = GbifClient(pool_size=max(32, args.workers * 2))

    LOG.info("fetching %s occurrence facets", args.country)
    counted = {
        key: count
        for key, count in client.occurrence_species_keys(country=args.country)
        if count >= args.min_records
    }
    LOG.info("%d species keys at or above %d records", len(counted), args.min_records)

    def backbone(key: int) -> dict:
        try:
            taxon = parse_backbone_record(client.species(key))
        except Exception as exc:  # noqa: BLE001 — one bad key must not end a 30,000-key run
            return {"key": key, "error": f"{type(exc).__name__}"}
        if taxon is None:
            return {"key": key, "error": "unparsed"}
        return {
            "key": taxon.key,
            "status": taxon.status,
            "rank": taxon.rank,
            "scientific_name": taxon.scientific_name,
            "lineage": taxon.lineage,
            "lineage_names": taxon.lineage_names,
        }

    records = fetch(
        cache_path(args.cache_dir, f"checklist_{args.country}.jsonl"),
        sorted(counted),
        backbone,
        args.workers,
        "backbone",
    )
    failed = [row for row in records.values() if "error" in row]
    if failed:
        LOG.warning("%d keys did not resolve: %s", len(failed), failed[:5])

    # The count is not on the backbone record, so it is put back before the filter — `wanted`
    # asks one question about one object rather than two callers agreeing on a convention.
    kept = []
    for key, row in records.items():
        if "error" in row:
            continue
        row = {**row, "records": counted.get(key, 0)}
        if wanted(row, args.min_records):
            kept.append(row)
    LOG.info("%d of %d resolved records belong on the checklist", len(kept), len(records))

    def vernacular(key: int) -> dict:
        try:
            found = client.vernacular_names(key)
        except Exception:  # noqa: BLE001
            return {"key": key, "en": None, "da": None}
        return {
            "key": key,
            "en": pick_vernacular(found, "eng"),
            "da": pick_vernacular(found, "dan"),
        }

    names: dict[int, dict] = {}
    if not args.no_vernaculars:
        names = fetch(
            cache_path(args.cache_dir, f"checklist_names_{args.country}.jsonl"),
            [int(row["key"]) for row in kept],
            vernacular,
            args.workers,
            "vernaculars",
        )

    # Family names too. A family is the headline of every row in the index, and "Anatidae"
    # where "Ducks, Geese, And Swans" belongs turns a browsable list into a taxonomy dump.
    # 544 of the 2,461 are already in the bundled taxonomy; the rest are one request each.
    family_index = families(kept)
    family_names: dict[int, dict] = {}
    if not args.no_vernaculars:
        family_names = fetch(
            cache_path(args.cache_dir, f"checklist_family_names_{args.country}.jsonl"),
            sorted(family_index),
            vernacular,
            args.workers,
            "family names",
        )
    family_index = {
        key: replace(family, vernacular_en=(family_names.get(key) or {}).get("en"))
        for key, family in family_index.items()
    }

    known = identifiable(args.taxonomy)
    species = [
        Species(
            key=int(row["key"]),
            scientific_name=str(row["scientific_name"]),
            family=family_key(row),
            records=int(row["records"]),
            vernacular_en=(names.get(int(row["key"])) or {}).get("en"),
            vernacular_da=(names.get(int(row["key"])) or {}).get("da"),
        )
        for row in kept
    ]
    unplaced = [one for one in species if one.family is None]

    written = document(
        species,
        family_index,
        known,
        country=args.country,
        min_records=args.min_records,
        fetched=date.today().isoformat(),
    )
    write_json(args.out, written)

    named = sum(1 for one in species if one.vernacular_en)
    families_named = sum(1 for f in family_index.values() if f.vernacular_en)
    LOG.info(
        "%s: %d species, %d families (%d named), %d the model can name, %d with an English "
        "name, %d placed no finer than an order",
        args.out,
        len(species),
        len(written["families"]),
        families_named,
        sum(1 for one in species if one.key in known),
        named,
        len(unplaced),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
