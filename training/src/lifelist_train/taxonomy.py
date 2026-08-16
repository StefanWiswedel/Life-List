"""Taxonomy tree — reference implementation of shared/taxonomy-spec.md §1.

Kotlin's `dk.lifelist.ml.Taxonomy` must match this behaviour exactly.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

SPEC_VERSION = 1

ROOT_ID = 0

RANK_ORDER: dict[str, int] = {
    "root": 0,
    "kingdom": 1,
    "phylum": 2,
    "class": 3,
    "order": 4,
    "family": 5,
    "genus": 6,
    "species": 7,
    "species_aggregate": 7,
    "subspecies": 8,
}


class TaxonomyError(ValueError):
    """Raised when a taxonomy violates a spec §1.1 invariant."""


@dataclass(slots=True)
class Taxon:
    taxon_id: int
    parent_id: int | None
    rank: str
    scientific_name: str
    vernacular_da: str | None = None
    vernacular_en: str | None = None
    leaf_index: int | None = None
    children: list[int] = field(default_factory=list)

    @property
    def is_leaf(self) -> bool:
        return self.leaf_index is not None


class Taxonomy:
    """Rooted taxonomic tree with leaf-index bookkeeping.

    Children are stored sorted by ``taxon_id`` so that tie-breaking (spec §4.2) and
    float accumulation order (spec §4.1) are deterministic and match Kotlin.
    """

    def __init__(self, taxa: list[Taxon]) -> None:
        self.nodes: dict[int, Taxon] = {t.taxon_id: t for t in taxa}
        if len(self.nodes) != len(taxa):
            raise TaxonomyError("duplicate taxon_id")

        roots = [t for t in taxa if t.parent_id is None]
        if len(roots) != 1:
            raise TaxonomyError(f"expected exactly one root, found {len(roots)}")
        self.root_id = roots[0].taxon_id

        for t in taxa:
            t.children = []
        for t in taxa:
            if t.parent_id is None:
                continue
            parent = self.nodes.get(t.parent_id)
            if parent is None:
                raise TaxonomyError(f"taxon {t.taxon_id} has missing parent {t.parent_id}")
            parent.children.append(t.taxon_id)
        for t in taxa:
            t.children.sort()

        self._validate()
        self._leaf_ids: list[int] = [
            tid for tid in sorted(self.nodes) if self.nodes[tid].is_leaf
        ]
        self._leaf_ids.sort(key=lambda tid: self.nodes[tid].leaf_index)
        # Precompute, for each node, the ascending list of descendant leaf indices.
        self._subtree_leaves: dict[int, list[int]] = {}
        self._build_subtree_leaves(self.root_id)

    # -- invariants (spec §1.1) -------------------------------------------------

    def _validate(self) -> None:
        # 2. acyclic + connected: every node reaches root
        for tid in self.nodes:
            seen = set()
            cur: int | None = tid
            while cur is not None:
                if cur in seen:
                    raise TaxonomyError(f"cycle detected at taxon {cur}")
                seen.add(cur)
                cur = self.nodes[cur].parent_id
            if self.root_id not in seen:
                raise TaxonomyError(f"taxon {tid} does not reach root")

        # 4. leaf_index set iff no children
        leaf_indices: list[int] = []
        for t in self.nodes.values():
            if bool(t.children) == t.is_leaf:
                raise TaxonomyError(
                    f"taxon {t.taxon_id}: leaf_index must be set iff the node has no children "
                    f"(children={len(t.children)}, leaf_index={t.leaf_index})"
                )
            if t.is_leaf:
                leaf_indices.append(t.leaf_index)

        # 3. leaf_index values are exactly 0..N-1
        if sorted(leaf_indices) != list(range(len(leaf_indices))):
            raise TaxonomyError("leaf_index values must be exactly 0..N_taxa-1, each used once")

        # 5. child rank strictly deeper than parent rank
        for t in self.nodes.values():
            if t.parent_id is None:
                continue
            parent = self.nodes[t.parent_id]
            if t.rank not in RANK_ORDER:
                raise TaxonomyError(f"taxon {t.taxon_id}: unknown rank {t.rank!r}")
            if parent.rank not in RANK_ORDER:
                raise TaxonomyError(f"taxon {parent.taxon_id}: unknown rank {parent.rank!r}")
            if RANK_ORDER[t.rank] <= RANK_ORDER[parent.rank]:
                raise TaxonomyError(
                    f"taxon {t.taxon_id} ({t.rank}) is not deeper than "
                    f"parent {parent.taxon_id} ({parent.rank})"
                )

    def _build_subtree_leaves(self, tid: int) -> list[int]:
        node = self.nodes[tid]
        if node.is_leaf:
            out = [node.leaf_index]
        else:
            out = []
            for child in node.children:
                out.extend(self._build_subtree_leaves(child))
            out.sort()  # spec §4.1: ascending leaf_index accumulation order
        self._subtree_leaves[tid] = out
        return out

    # -- accessors --------------------------------------------------------------

    @property
    def n_taxa(self) -> int:
        """Number of leaf taxa — the head's output dimension."""
        return len(self._leaf_ids)

    def __len__(self) -> int:
        return len(self.nodes)

    def node(self, taxon_id: int) -> Taxon:
        return self.nodes[taxon_id]

    def leaf_id(self, leaf_index: int) -> int:
        return self._leaf_ids[leaf_index]

    def subtree_leaf_indices(self, taxon_id: int) -> list[int]:
        return self._subtree_leaves[taxon_id]

    def lineage(self, taxon_id: int) -> list[int]:
        """Root-first path from root to ``taxon_id`` inclusive."""
        out: list[int] = []
        cur: int | None = taxon_id
        while cur is not None:
            out.append(cur)
            cur = self.nodes[cur].parent_id
        out.reverse()
        return out

    def is_ancestor_or_self(self, candidate: int, target: int) -> bool:
        """True if ``candidate`` lies on the root-to-``target`` path.

        This is the predicate behind the rollup-accuracy metric (build prompt §5).
        """
        cur: int | None = target
        while cur is not None:
            if cur == candidate:
                return True
            cur = self.nodes[cur].parent_id
        return False

    # -- io ---------------------------------------------------------------------

    @classmethod
    def from_json(cls, path: str | Path) -> Taxonomy:
        with open(path, encoding="utf-8") as fh:
            raw = json.load(fh)
        return cls.from_dict(raw)

    @classmethod
    def from_dict(cls, raw: dict) -> Taxonomy:
        version = raw.get("spec_version")
        if version != SPEC_VERSION:
            raise TaxonomyError(
                f"taxonomy.json spec_version {version!r} != implemented {SPEC_VERSION}"
            )
        return cls([Taxon(**t) for t in raw["taxa"]])

    def to_dict(self) -> dict:
        return {
            "spec_version": SPEC_VERSION,
            "taxa": [
                {
                    "taxon_id": t.taxon_id,
                    "parent_id": t.parent_id,
                    "rank": t.rank,
                    "scientific_name": t.scientific_name,
                    "vernacular_da": t.vernacular_da,
                    "vernacular_en": t.vernacular_en,
                    "leaf_index": t.leaf_index,
                }
                for t in sorted(self.nodes.values(), key=lambda x: x.taxon_id)
            ],
        }
