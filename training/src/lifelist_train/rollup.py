"""Taxonomic rollup — reference implementation of shared/taxonomy-spec.md §4.

Kotlin's `dk.lifelist.ml.Rollup` must produce identical output for identical input.
The golden fixture in `shared/golden/golden_rollup.json` is the cross-language check.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .taxonomy import Taxonomy

DEFAULT_THRESHOLD = 0.70
MIN_THRESHOLD = 0.50
MAX_THRESHOLD = 0.95
N_CANDIDATES = 5


@dataclass(frozen=True, slots=True)
class Candidate:
    taxon_id: int
    leaf_index: int
    probability: float


@dataclass(frozen=True, slots=True)
class RollupResult:
    taxon_id: int
    rank: str
    probability: float
    candidates: tuple[Candidate, ...]
    threshold: float

    @property
    def is_unidentified(self) -> bool:
        """True when nothing cleared threshold even at kingdom level (spec §4.2)."""
        return self.rank == "root"


def node_probabilities(tax: Taxonomy, p: np.ndarray) -> dict[int, float]:
    """P(n) for every node: the sum of its descendant leaves (spec §4.1).

    Accumulated in ascending leaf_index order in float64, per the determinism
    requirement — Kotlin must do the same or the golden test drifts.
    """
    _check_leaf_probs(tax, p)
    p64 = p.astype(np.float64, copy=False)
    out: dict[int, float] = {}
    for taxon_id in tax.nodes:
        idx = tax.subtree_leaf_indices(taxon_id)
        out[taxon_id] = float(np.sum(p64[idx]))
    return out


def rollup(
    tax: Taxonomy,
    p: np.ndarray,
    threshold: float = DEFAULT_THRESHOLD,
    n_candidates: int = N_CANDIDATES,
) -> RollupResult:
    """Descend to the deepest node whose probability clears ``threshold``.

    Returns the root when nothing clears it, which the UI renders as "cannot
    identify" rather than as a bad guess.
    """
    if not MIN_THRESHOLD <= threshold <= MAX_THRESHOLD:
        raise ValueError(
            f"threshold {threshold} outside the settable range "
            f"[{MIN_THRESHOLD}, {MAX_THRESHOLD}]"
        )
    probs = node_probabilities(tax, p)

    node_id = tax.root_id
    while True:
        children = tax.node(node_id).children
        if not children:
            break
        # max by probability, ties broken by lower taxon_id (spec §4.2).
        # `children` is already sorted ascending, so a strict > keeps the first.
        best = children[0]
        for child in children[1:]:
            if probs[child] > probs[best]:
                best = child
        if probs[best] >= threshold:
            node_id = best
        else:
            break

    node = tax.node(node_id)
    return RollupResult(
        taxon_id=node_id,
        rank=node.rank,
        probability=float(np.float32(probs[node_id])),
        candidates=top_candidates(tax, p, n_candidates),
        threshold=float(threshold),
    )


def top_candidates(tax: Taxonomy, p: np.ndarray, n: int = N_CANDIDATES) -> tuple[Candidate, ...]:
    """Top-``n`` leaves by probability, descending; ties break by lower taxon_id.

    Not restricted to the returned node's subtree — see spec §4.3.
    """
    _check_leaf_probs(tax, p)
    order = sorted(
        range(len(p)),
        key=lambda i: (-float(p[i]), tax.leaf_id(i)),
    )
    return tuple(
        Candidate(
            taxon_id=tax.leaf_id(i),
            leaf_index=i,
            probability=float(np.float32(p[i])),
        )
        for i in order[:n]
    )


def is_rollup_correct(tax: Taxonomy, result: RollupResult, true_leaf_id: int) -> bool:
    """Rollup accuracy predicate (build prompt §5): is the answer an honest ancestor?

    Returning `Carabus` for a *Carabus granulatus* is correct. Returning the root is
    not — an app that always says "don't know" would otherwise score 100%.
    """
    if result.is_unidentified:
        return False
    return tax.is_ancestor_or_self(result.taxon_id, true_leaf_id)


def _check_leaf_probs(tax: Taxonomy, p: np.ndarray) -> None:
    if p.ndim != 1:
        raise ValueError(f"expected a 1-D leaf probability vector, got shape {p.shape}")
    if len(p) != tax.n_taxa:
        raise ValueError(
            f"probability vector has {len(p)} entries but the taxonomy has {tax.n_taxa} leaves"
        )
    if np.any(p < 0):
        raise ValueError("probability vector contains negative entries")
    total = float(np.sum(p.astype(np.float64)))
    if not np.isclose(total, 1.0, atol=1e-4):
        raise ValueError(f"probability vector sums to {total}, expected 1.0")
