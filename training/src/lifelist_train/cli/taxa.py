"""Stage 1 — build the Danish taxon list from GBIF.

    lifelist-taxa --country DK --max-taxa 5000 --cache-dir cache

Produces `taxa_raw.json` (GBIF records plus synonyms) for stage 2. The taxonomy tree
itself is only assembled once stage 2 has decided which taxa clear the image threshold —
building it here would bake in taxa we then discard, and the leaf indices must match the
model's output dimension exactly.
"""

from __future__ import annotations

import argparse
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor

from ..gbif import GbifClient, collect_synonyms, parse_backbone_record, pick_vernacular
from ._common import LOG, add_common_args, cache_path, load_json, setup_logging, write_json


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Build the Danish taxon candidate list")
    parser.add_argument("--country", default="DK")
    parser.add_argument(
        "--max-taxa",
        type=int,
        default=20000,
        help="stop after this many species keys, commonest first",
    )
    parser.add_argument(
        "--min-occurrences",
        type=int,
        default=10,
        help="skip species with fewer than this many national occurrence records",
    )
    parser.add_argument("--no-vernaculars", action="store_true", help="skip vernacular lookup")
    parser.add_argument(
        "--workers",
        type=int,
        default=8,
        help="concurrent GBIF requests. 1 restores the original serial behaviour.",
    )
    return add_common_args(parser)


def fetch_taxon(
    client: GbifClient, key: int, count: int, with_vernaculars: bool = True
) -> tuple[dict | None, list[tuple[str, int]]]:
    """Everything stage 1 needs for one species key: record, vernaculars, synonyms.

    Returns `(None, [])` for a key that fails or that the backbone parser rejects — one
    bad key out of twenty thousand must not end a run that costs an hour.
    """
    try:
        record = client.species(key)
    except Exception as exc:  # noqa: BLE001 — one bad key must not end the run
        LOG.warning("species %s failed: %s", key, exc)
        return None, []

    taxon = parse_backbone_record(record)
    if taxon is None:
        return None, []

    vernacular_en = vernacular_da = None
    if with_vernaculars:
        try:
            names = client.vernacular_names(key)
            vernacular_en = pick_vernacular(names, "eng")
            vernacular_da = pick_vernacular(names, "dan")
        except Exception as exc:  # noqa: BLE001
            LOG.debug("vernaculars for %s failed: %s", key, exc)

    try:
        synonyms = collect_synonyms(client.synonyms(key))
    except Exception as exc:  # noqa: BLE001
        LOG.debug("synonyms for %s failed: %s", key, exc)
        synonyms = []

    return (
        {
            "key": taxon.key,
            "scientific_name": taxon.scientific_name,
            "rank": taxon.rank,
            "status": taxon.status,
            "lineage": taxon.lineage,
            "lineage_names": taxon.lineage_names,
            "vernacular_en": vernacular_en,
            "vernacular_da": vernacular_da,
            "occurrences": count,
        },
        synonyms,
    )


def fetch_taxa(
    client: GbifClient,
    keys: list[tuple[int, int]],
    workers: int = 8,
    with_vernaculars: bool = True,
) -> tuple[list[dict], list[tuple[str, int]], int]:
    """Fetch every key, concurrently, in input order.

    Order is the point: `ThreadPoolExecutor.map` yields results in the order the inputs
    were submitted, so `taxa_raw.json` is byte-identical whether this runs with one
    worker or sixteen. Leaf indices are later assigned from this ordering, and a set
    that reshuffles between runs would silently invalidate every exported model.
    """
    taxa: list[dict] = []
    synonyms: list[tuple[str, int]] = []
    skipped = 0

    def one(item: tuple[int, int]) -> tuple[dict | None, list[tuple[str, int]]]:
        return fetch_taxon(client, item[0], item[1], with_vernaculars)

    if workers <= 1:
        results: Iterator = map(one, keys)
    else:
        pool = ThreadPoolExecutor(max_workers=workers)
        results = pool.map(one, keys)

    try:
        for position, (taxon, taxon_synonyms) in enumerate(results, start=1):
            if position % 250 == 0:
                LOG.info("  %d/%d", position, len(keys))
            if taxon is None:
                skipped += 1
                continue
            taxa.append(taxon)
            synonyms.extend(taxon_synonyms)
    finally:
        if workers > 1:
            pool.shutdown(wait=False, cancel_futures=True)

    return taxa, synonyms, skipped


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    out_path = cache_path(args.cache_dir, "taxa_raw.json")
    if out_path.exists() and not args.force:
        cached = load_json(out_path)
        LOG.info(
            "using cached %s (%d taxa) — pass --force to rebuild",
            out_path,
            len(cached["taxa"]),
        )
        return 0

    client = GbifClient(pool_size=max(16, args.workers * 2))

    LOG.info("fetching %s occurrence facets", args.country)
    keys: list[tuple[int, int]] = []
    for key, count in client.occurrence_species_keys(country=args.country):
        if count < args.min_occurrences:
            continue
        keys.append((key, count))
        if len(keys) >= args.max_taxa:
            break
    LOG.info("%d species keys above %d occurrences", len(keys), args.min_occurrences)

    taxa, synonyms, skipped = fetch_taxa(
        client,
        keys,
        workers=args.workers,
        with_vernaculars=not args.no_vernaculars,
    )

    write_json(
        out_path,
        {
            "country": args.country,
            "taxa": taxa,
            "synonyms": [{"name": n, "accepted_key": k} for n, k in synonyms],
        },
    )

    LOG.info(
        "%d taxa, %d synonyms, %d skipped. Next: lifelist-images --cache-dir %s",
        len(taxa),
        len(synonyms),
        skipped,
        args.cache_dir,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
