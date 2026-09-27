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
from ..register import Register
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
    parser.add_argument(
        "--all-records",
        action="store_true",
        help=(
            "count every kind of GBIF record, not only observations. This is what the first "
            "version did and it put the African buffalo, the lowland anoa and a Pleistocene "
            "bison on Denmark's mammal list, because a museum drawer in Copenhagen is an "
            "occurrence 'in Denmark'. 7,006 of the 26,722 species it produced had never been "
            "seen alive here at all. See VERIFICATION.md section 89."
        ),
    )
    parser.add_argument(
        "--register",
        type=Path,
        default=Path("cache/danish_register.json"),
        help=(
            "Denmark's national species register, from `lifelist-register`. GBIF says what "
            "was seen here; this says what counts as living here, which is how the cattle, "
            "the budgerigars and the peafowl leave without anyone curating a list (§90)."
        ),
    )
    parser.add_argument(
        "--no-register",
        action="store_true",
        help="keep every observed species, register or not",
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


def vouched_for(kept: list[dict], args, client: GbifClient) -> list[dict]:
    """Keep the species Denmark's own register knows, under any name.

    Three clauses, cheapest first, and only the ones still unvouched after the first two cost
    a request — 700 of 14,876 rather than one per name in a 60,000-name register.
    """
    if args.no_register:
        LOG.warning("--no-register: livestock and escaped cage birds stay on the list")
        return kept
    if not args.register.exists():
        LOG.error(
            "no %s — run `lifelist-register` first, or pass --no-register on purpose",
            args.register,
        )
        raise SystemExit(2)

    register = Register.from_document(json.loads(args.register.read_text(encoding="utf-8")))
    if not register.usable:
        LOG.error("%s vouches for almost nothing — refusing to filter Denmark away", args.register)
        raise SystemExit(2)

    unsure = [row for row in kept if register.needs_synonyms(row["scientific_name"])]
    LOG.info(
        "register: %d names, %d genera — %d species want a synonym lookup",
        len(register.names), len(register.genera), len(unsure),
    )

    def synonyms_of(key: int) -> dict:
        try:
            names = [s.get("scientificName", "") for s in client.synonyms(key)]
        except Exception as exc:  # noqa: BLE001 — one bad key must not end the run
            return {"key": key, "error": f"{type(exc).__name__}"}
        return {"key": key, "synonyms": names}

    rows = fetch(
        cache_path(args.cache_dir, f"checklist_synonyms_{args.country}.jsonl"),
        sorted(row["key"] for row in unsure),
        synonyms_of,
        args.workers,
        "synonyms",
    )

    out = []
    for row in kept:
        names = rows.get(row["key"], {}).get("synonyms", ())
        if register.vouches(row["scientific_name"], names):
            out.append(row)
    LOG.info(
        "%d of %d species are in Denmark's national register; %d are not",
        len(out), len(kept), len(kept) - len(out),
    )
    # The commonest few, named in the log. A filter that removes 300 species silently is a
    # filter nobody checks; one that says it dropped the domestic cat is one you can argue with.
    survived = {r["key"] for r in out}
    dropped = sorted(
        (r for r in kept if r["key"] not in survived), key=lambda r: -r["records"]
    )[:10]
    for row in dropped:
        LOG.info("  not in the register: %s (%d records)", row["scientific_name"], row["records"])
    return out


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)
    client = GbifClient(pool_size=max(32, args.workers * 2))

    basis = None if args.all_records else GbifClient.SEEN
    LOG.info(
        "fetching %s occurrence facets (%s)",
        args.country, "every record" if basis is None else "observations only",
    )
    counted = {
        key: count
        for key, count in client.occurrence_species_keys(
            country=args.country, basis=basis, present_only=not args.all_records
        )
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

    kept = vouched_for(kept, args, client)

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
