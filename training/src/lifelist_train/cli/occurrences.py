"""How often each species is recorded in Denmark — VERIFICATION §72.

    lifelist-occurrences -v
    lifelist-occurrences --commit

One pass over GBIF's Danish occurrence facets: 52,000 species keys in about eighty seconds,
of which 3,754 are ours. Stage 1 already asks this endpoint the same question and throws the
counts away after using them as a filter (`cli/taxa.py`); this keeps them.

A species of ours that never appears in the facet has **no Danish records at all**, which is
written down as zero rather than left out. Most of the 227 are birds BirdNET can hear and
Denmark has never had, and "never recorded here" is among the more interesting things this
file can say.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from ..gbif import GbifClient
from ..occurrences import (
    document,
    family_of,
    phrase,
    sorted_by_effort,
    standings,
    summarise,
)
from ._common import LOG, setup_logging, shared_model, write_json

Tree = tuple[dict[int, int | None], dict[int, str], dict[int, str], set[int]]


def tree(paths: list[Path]) -> Tree:
    """parents, ranks, names, and the species leaves worth counting."""
    parents: dict[int, int | None] = {}
    ranks: dict[int, str] = {}
    names: dict[int, str] = {}
    leaves: set[int] = set()
    for path in paths:
        if not path.exists():
            LOG.warning("no %s — skipping it", path)
            continue
        for node in json.loads(path.read_text(encoding="utf-8")):
            taxon_id = int(node["taxon_id"])
            parents.setdefault(taxon_id, node.get("parent_id"))
            ranks.setdefault(taxon_id, str(node["rank"]))
            names.setdefault(taxon_id, str(node["scientific_name"]))
            # Synthetic `sp.` leaves are a class, not a species anybody records.
            if node.get("leaf_index") is not None and taxon_id > 0:
                leaves.add(taxon_id)
    return parents, ranks, names, leaves


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--taxonomy", type=Path, action="append")
    ap.add_argument("--out", type=Path, default=shared_model("occurrences.json"))
    ap.add_argument("--country", default="DK")
    ap.add_argument("--max-keys", type=int, default=80_000)
    ap.add_argument("--commit", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    setup_logging(args.verbose)

    taxonomies = args.taxonomy or [
        shared_model("taxonomy.json"),
        shared_model("audio_taxonomy.json"),
    ]
    parents, ranks, names, leaves = tree(taxonomies)
    LOG.info("%d species across %d taxonomies", len(leaves), len(taxonomies))

    client = GbifClient(pause_s=0.05)
    counts = {taxon_id: 0 for taxon_id in leaves}
    scanned = 0
    for key, count in client.occurrence_species_keys(country=args.country):
        scanned += 1
        if key in counts:
            counts[key] = count
        if scanned >= args.max_keys:
            LOG.warning("stopped at %d keys — raise --max-keys if that is short", scanned)
            break
    LOG.info(
        "%d %s species keys scanned; %d of ours have records, %d have none",
        scanned,
        args.country,
        sum(1 for c in counts.values() if c),
        sum(1 for c in counts.values() if not c),
    )

    families = {
        taxon_id: family
        for taxon_id in counts
        if (family := family_of(taxon_id, parents, ranks)) is not None
    }
    missing_family = len(counts) - len(families)
    if missing_family:
        LOG.info("%d species have no family and cannot be ranked against one", missing_family)

    ranked = standings(counts, families)
    for label, value in sorted(summarise(ranked).items()):
        print(f"  {label}: {value:,}")

    print("\nLeast recorded of the ones we know:")
    for standing in sorted_by_effort(ranked)[:12]:
        family = names.get(families.get(standing.taxon_id, 0), "of its family")
        print(f"  {names.get(standing.taxon_id, standing.taxon_id):32s} {phrase(standing, family)}")

    if not args.commit:
        print("\nNothing written. Re-run with --commit.")
        return 0

    write_json(args.out, document(ranked, args.country))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
