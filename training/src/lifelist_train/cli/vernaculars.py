"""Fill in the English names the taxonomy is missing — VERIFICATION §71.

    lifelist-vernaculars -v
    lifelist-vernaculars --commit

371 of the 3,482 species the camera knows have no English name, so the app prints a bare
binomial where every other row has a name somebody would actually say. GBIF supplied what it
had when the taxonomy was built (§28); iNaturalist knows a few hundred more, because its names
come from the people using it rather than from a nomenclatural authority.

**Only the empty fields are touched.** A name already in the taxonomy was chosen by GBIF's own
vernacular list and is not up for revision by a second source — this fills gaps, it does not
arbitrate.

**And leaf ordering must not move.** The shipped head is a vector of 3,482 logits whose meaning
is positional; a taxonomy whose leaves reordered would silently relabel every prediction. The
stage refuses to write if the order changed, the same guard `--taxonomy-only` has (§42).
"""

from __future__ import annotations

import argparse
import json
import time
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

from ..gbif import tidy_vernacular
from ._common import LOG, setup_logging, shared_model, write_json

API = "https://api.inaturalist.org/v1/taxa"
USER_AGENT = "LifeList/0.11 (https://github.com/StefanWiswedel/Life-List)"
BATCH = 30
PAUSE = 1.0


def leaf_order(nodes: Sequence[Mapping[str, Any]]) -> list[int]:
    """Taxon ids in leaf_index order — the thing that must not move."""
    leaves = [n for n in nodes if n.get("leaf_index") is not None]
    return [int(n["taxon_id"]) for n in sorted(leaves, key=lambda n: int(n["leaf_index"]))]


def missing(nodes: Sequence[Mapping[str, Any]], leaves_only: bool = True) -> dict[int, str]:
    """Taxon id → scientific name, for everything with no English name."""
    return {
        int(n["taxon_id"]): str(n["scientific_name"])
        for n in nodes
        if not n.get("vernacular_en")
        and (not leaves_only or n.get("leaf_index") is not None)
    }


def inat_ids(bridges: Sequence[Path]) -> dict[int, int]:
    """GBIF id → iNaturalist id, from every crossing we have."""
    out: dict[int, int] = {}
    for path in bridges:
        if not path.exists():
            continue
        for inat, gbif in json.loads(path.read_text(encoding="utf-8"))["mapping"].items():
            out.setdefault(int(gbif), int(inat))
    return out


def names_from(
    records: Mapping[int, Mapping[str, Any]],
    wanted: Mapping[int, int],
) -> dict[int, str]:
    """GBIF id → the English name iNaturalist prefers, where it has one.

    Tidied the same way GBIF's are (§28's `tidy_vernacular`): iNaturalist's names arrive in
    every casing its users typed — "speckled longhorn beetle" beside "Two-banded Longhorn
    Beetle" — and two capitalisation styles in one list reads as a bug in us.
    """
    out: dict[int, str] = {}
    for gbif, inat in wanted.items():
        preferred = (records.get(inat) or {}).get("preferred_common_name")
        tidied = tidy_vernacular(preferred) if preferred else None
        if tidied:
            out[gbif] = tidied
    return out


def fetch(ids: Sequence[int], get: Callable[[Sequence[int]], list[dict[str, Any]]],
          batch: int = BATCH) -> dict[int, dict[str, Any]]:
    """Every taxon, thirty at a time. One dead batch costs that batch."""
    out: dict[int, dict[str, Any]] = {}
    for start in range(0, len(ids), batch):
        for record in get(ids[start : start + batch]):
            out[int(record["id"])] = record
    return out


def getter(session: Any = None) -> Callable[[Sequence[int]], list[dict[str, Any]]]:
    import requests

    session = session or requests.Session()

    def get(ids: Sequence[int]) -> list[dict[str, Any]]:
        url = f"{API}/{','.join(str(i) for i in ids)}"
        for attempt in range(5):
            try:
                response = session.get(url, headers={"User-Agent": USER_AGENT}, timeout=60)
                if response.status_code == 429:
                    time.sleep(min(float(response.headers.get("Retry-After") or 20), 60))
                    continue
                response.raise_for_status()
                time.sleep(PAUSE)
                return list(response.json().get("results") or [])
            except Exception as exc:  # noqa: BLE001 — one bad batch must not end the run
                LOG.debug("attempt %d failed: %s", attempt, exc)
                time.sleep(min(5 * (attempt + 1), 60))
        LOG.warning("gave up on a batch of %d", len(ids))
        return []

    return get


def apply(nodes: list[dict[str, Any]], found: Mapping[int, str]) -> int:
    """Write the names in. Returns how many were filled."""
    filled = 0
    for node in nodes:
        name = found.get(int(node["taxon_id"]))
        if name and not node.get("vernacular_en"):
            node["vernacular_en"] = name
            filled += 1
    return filled


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--taxonomy", type=Path, action="append")
    ap.add_argument("--bridge", type=Path, action="append")
    ap.add_argument("--commit", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    setup_logging(args.verbose)

    taxonomies = args.taxonomy or [
        shared_model("taxonomy.json"),
        shared_model("audio_taxonomy.json"),
    ]
    bridges = args.bridge or [
        shared_model("taxon_bridge.json"),
        shared_model("audio_bridge.json"),
    ]
    crossing = inat_ids(bridges)
    get = getter()

    for path in taxonomies:
        if not path.exists():
            LOG.warning("no %s — skipping it", path)
            continue
        nodes = json.loads(path.read_text(encoding="utf-8"))
        before = leaf_order(nodes)

        gaps = missing(nodes)
        wanted = {gbif: crossing[gbif] for gbif in gaps if gbif in crossing}
        LOG.info(
            "%s: %d leaves without an English name, %d of them crossed to iNaturalist",
            path.name, len(gaps), len(wanted),
        )
        if not wanted:
            continue

        records = fetch(sorted(wanted.values()), get)
        found = names_from(records, wanted)
        LOG.info("iNaturalist has a name for %d of %d", len(found), len(wanted))
        for gbif, name in sorted(found.items())[:15]:
            print(f"  {gaps[gbif]:34s} -> {name}")
        if len(found) > 15:
            print(f"  ... and {len(found) - 15} more")

        filled = apply(nodes, found)
        after = leaf_order(nodes)
        if before != after:
            LOG.error("leaf ordering moved — refusing to write. The shipped head would be wrong.")
            return 1
        LOG.info("%d filled; %d still have none", filled, len(gaps) - filled)

        if args.commit:
            # Through the shared writer, so the file comes back in the layout the taxonomy
            # stage wrote it in. Writing it any other way reformats all 60,000 lines and turns
            # a 142-line change into a diff nobody can read — which .gitattributes went to some
            # trouble to prevent for exactly this reason.
            write_json(path, nodes)

    if not args.commit:
        print("\nNothing written. Re-run with --commit.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
