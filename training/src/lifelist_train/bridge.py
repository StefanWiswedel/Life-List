"""Crossing from iNaturalist's taxon ids to GBIF's.

The photographs are labelled with iNaturalist taxon ids. The taxonomy the app reasons over is
GBIF's. The two id spaces are unrelated — iNat 1688020 and GBIF 1688020 are different organisms —
and nothing links them but the scientific name, which is why `names.py` exists and why it took
three attempts to get from 83% to 97.2% of photographs matched.

That bridge used to live in a throwaway script. It produced the model that shipped, and it was
the third committed artefact in this repo with no committed builder (§28, §37). This is it,
written down, with the crossing itself precomputed into `shared/model/taxon_bridge.json` so that
**stage 4 needs neither the 31 MB GBIF dump nor the 39 MB iNaturalist taxa table** — a laptop
with embeddings and a manifest can train a head.

Two rules carried over from `names.py`, both earned:

- **A homonym is refused, never guessed.** *Prunella* is a plant genus and a bird genus. Picking
  one silently is how a beetle gets filed as a plant.
- **A genus-rank label becomes an indeterminate leaf**, not a species — spec §1.1a. A photograph
  identified only to *Carabus* is evidence about the genus, and forcing it onto some species
  underneath would be inventing data.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from .gbif import GbifTaxon, indeterminate_id
from .names import SynonymIndex


@dataclass(frozen=True, slots=True)
class BridgeReport:
    """Why each taxon did or did not cross. Printed, not just counted."""

    bridged: int
    ambiguous: int
    unresolved: int
    genus_ambiguous: int
    unknown_id: int

    @property
    def attempted(self) -> int:
        return (
            self.bridged
            + self.ambiguous
            + self.unresolved
            + self.genus_ambiguous
            + self.unknown_id
        )

    def summary(self) -> str:
        rate = self.bridged / self.attempted if self.attempted else 0.0
        return (
            f"bridged {self.bridged}/{self.attempted} ({rate:.1%}) — "
            f"{self.ambiguous} homonyms refused, {self.unresolved} unmatched, "
            f"{self.genus_ambiguous} ambiguous genera, {self.unknown_id} unknown ids"
        )


def genus_keys_by_name(taxa: Iterable[Mapping[str, Any]]) -> dict[str, set[int]]:
    """Genus name → the GBIF genus keys that use it.

    A set rather than a key, because more than one is the case that must be refused. Built from
    the *lineage* of fetched taxa rather than from genus records, since a genus is often only
    present as somebody's ancestor.
    """
    out: dict[str, set[int]] = {}
    for taxon in taxa:
        key = (taxon.get("lineage") or {}).get("genus")
        name = (taxon.get("lineage_names") or {}).get("genus")
        if key and name:
            out.setdefault(str(name).strip().lower(), set()).add(int(key))
    return out


def build_mapping(
    inat_taxa: Iterable[tuple[int, str, str]],
    index: SynonymIndex,
    genus_keys: Mapping[str, set[int]],
) -> tuple[dict[int, int], set[int], BridgeReport]:
    """(iNat id, name, rank) → {iNat id: GBIF key}, and the genera needing an indeterminate leaf.

    A negative key is an indeterminate leaf: `-1036775` is "some *Carabus*", spec §1.1a.
    """
    mapping: dict[int, int] = {}
    parents: set[int] = set()
    ambiguous = unresolved = genus_ambiguous = unknown = 0

    for taxon_id, name, rank in inat_taxa:
        if not name:
            unknown += 1
            continue

        if rank == "genus":
            candidates = genus_keys.get(name.strip().lower(), set())
            if len(candidates) == 1:
                key = next(iter(candidates))
                parents.add(key)
                mapping[int(taxon_id)] = indeterminate_id(key)
            else:
                # Zero means GBIF never saw this genus in Denmark; more than one means a
                # homonym. Neither is a thing to guess at.
                genus_ambiguous += 1
            continue

        if index.is_ambiguous(name):
            ambiguous += 1
            continue

        key = index.resolve(name)
        if key is None:
            unresolved += 1
        else:
            mapping[int(taxon_id)] = key

    return (
        mapping,
        parents,
        BridgeReport(len(mapping), ambiguous, unresolved, genus_ambiguous, unknown),
    )


def needed_keys(
    mapping: Mapping[int, int],
    parents: Iterable[int],
    records: Mapping[int, Mapping[str, Any]] | None = None,
) -> set[int]:
    """Every GBIF key whose record the taxonomy builder will need.

    Leaves, the genera with an indeterminate leaf, and — when `records` is given — every
    ancestor in their lineages.

    **The ancestors were left out at first, and that was wrong.** `build_taxonomy_nodes` does
    reconstruct them from each leaf's `lineage` and `lineage_names`, so the taxonomy had the
    right shape and the right scientific names. What a lineage does not carry is the common
    name, so the ≥20 taxonomy shipped with **0 of 691 families** having one — "Anatidae" with
    no "Ducks, Geese and Swans" behind it, all the way up the tree. Costing a megabyte of
    records to keep every higher rank readable is not a close call.
    """
    wanted = {abs(key) for key in mapping.values()} | set(parents)
    if records is None:
        return wanted
    for key in list(wanted):
        record = records.get(key)
        for ancestor in (record or {}).get("lineage", {}).values():
            wanted.add(int(ancestor))
    return wanted


def document(
    mapping: Mapping[int, int],
    parents: Iterable[int],
    records: Mapping[int, Mapping[str, Any]],
) -> dict[str, Any]:
    """The shipped artefact.

    Keys are strings because JSON has no other kind, and negative ids round-trip through that
    perfectly well as long as nobody assumes otherwise on the way back in.
    """
    wanted = needed_keys(mapping, parents, records)
    return {
        "mapping": {str(k): int(v) for k, v in sorted(mapping.items())},
        "indeterminate_parents": sorted(int(p) for p in parents if int(p) in records),
        "taxa": [
            {
                "key": int(k),
                "scientific_name": records[k]["scientific_name"],
                "rank": records[k]["rank"],
                "status": records[k].get("status", "ACCEPTED"),
                "lineage": records[k].get("lineage") or {},
                "lineage_names": records[k].get("lineage_names") or {},
                "vernacular_en": records[k].get("vernacular_en"),
                "vernacular_da": records[k].get("vernacular_da"),
            }
            for k in sorted(wanted)
            if k in records
        ],
    }


def load(doc: Mapping[str, Any]) -> tuple[dict[int, int], list[int], list[GbifTaxon]]:
    """Read the artefact back: mapping, indeterminate parents, GBIF records."""
    mapping = {int(k): int(v) for k, v in doc["mapping"].items()}
    parents = [int(p) for p in doc.get("indeterminate_parents", [])]
    taxa = [
        GbifTaxon(
            key=int(t["key"]),
            scientific_name=t["scientific_name"],
            rank=t["rank"],
            status=t.get("status", "ACCEPTED"),
            lineage={k: int(v) for k, v in (t.get("lineage") or {}).items()},
            lineage_names=dict(t.get("lineage_names") or {}),
            vernacular_en=t.get("vernacular_en"),
            vernacular_da=t.get("vernacular_da"),
        )
        for t in doc["taxa"]
    ]
    return mapping, parents, taxa


def restrict(
    doc: Mapping[str, Any],
    keep_inat_ids: Sequence[int],
) -> tuple[dict[int, int], list[int], list[GbifTaxon]]:
    """The same artefact, narrowed to a subset of iNaturalist taxa.

    This is what makes "embed once at ≥20, then train heads at 20/30/40/50" possible: every
    higher threshold is a subset of the taxa the bridge already covers, so no refetching and no
    second artefact. A genus keeps its indeterminate leaf only if that leaf survives the
    narrowing — otherwise the taxonomy would grow a leaf with no photographs behind it.
    """
    mapping, parents, taxa = load(doc)
    keep = {int(i) for i in keep_inat_ids}
    narrowed = {k: v for k, v in mapping.items() if k in keep}
    surviving = {-v for v in narrowed.values() if v < 0}
    by_key = {
        t.key: {"lineage": t.lineage} for t in taxa
    }
    wanted = needed_keys(narrowed, surviving, by_key)
    return (
        narrowed,
        [p for p in parents if p in surviving],
        [t for t in taxa if t.key in wanted],
    )
