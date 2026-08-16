"""Tests for the rollup algorithm — shared/taxonomy-spec.md §4."""

from __future__ import annotations

import numpy as np
import pytest

from lifelist_train.rollup import (
    is_rollup_correct,
    node_probabilities,
    rollup,
    top_candidates,
)
from lifelist_train.taxonomy import Taxon, Taxonomy, TaxonomyError


def build_beetles() -> Taxonomy:
    """A small real-shaped tree: two carabid species, a staphylinid, and a plant.

        root
        ├── Animalia > Arthropoda > Insecta > Coleoptera
        │   ├── Carabidae > Carabus > {granulatus[0], nemoralis[1]}
        │   └── Staphylinidae > Ocypus > olens[2]
        └── Plantae > Tracheophyta > Magnoliopsida > Asterales
            > Asteraceae > Bellis > perennis[3]
    """
    return Taxonomy(
        [
            Taxon(0, None, "root", "Life"),
            # animals
            Taxon(1, 0, "kingdom", "Animalia"),
            Taxon(2, 1, "phylum", "Arthropoda"),
            Taxon(3, 2, "class", "Insecta"),
            Taxon(4, 3, "order", "Coleoptera"),
            Taxon(5, 4, "family", "Carabidae"),
            Taxon(6, 5, "genus", "Carabus"),
            Taxon(7, 6, "species", "Carabus granulatus", leaf_index=0),
            Taxon(8, 6, "species", "Carabus nemoralis", leaf_index=1),
            Taxon(9, 4, "family", "Staphylinidae"),
            Taxon(10, 9, "genus", "Ocypus"),
            Taxon(11, 10, "species", "Ocypus olens", leaf_index=2),
            # plants
            Taxon(20, 0, "kingdom", "Plantae"),
            Taxon(21, 20, "phylum", "Tracheophyta"),
            Taxon(22, 21, "class", "Magnoliopsida"),
            Taxon(23, 22, "order", "Asterales"),
            Taxon(24, 23, "family", "Asteraceae"),
            Taxon(25, 24, "genus", "Bellis"),
            Taxon(26, 25, "species", "Bellis perennis", leaf_index=3),
        ]
    )


# -- node probabilities ---------------------------------------------------------


def test_node_probabilities_sum_to_parent():
    tax = build_beetles()
    p = np.array([0.4, 0.3, 0.2, 0.1], dtype=np.float32)
    probs = node_probabilities(tax, p)

    assert probs[0] == pytest.approx(1.0)
    assert probs[6] == pytest.approx(0.7)  # Carabus = granulatus + nemoralis
    assert probs[5] == pytest.approx(0.7)  # Carabidae has only Carabus
    assert probs[4] == pytest.approx(0.9)  # Coleoptera = carabids + Ocypus
    assert probs[1] == pytest.approx(0.9)  # Animalia
    assert probs[20] == pytest.approx(0.1)  # Plantae


def test_probability_is_monotone_non_increasing_with_depth():
    """Spec §4.2 — a parent is the sum of its children, so descent terminates."""
    tax = build_beetles()
    rng = np.random.default_rng(0)
    for _ in range(50):
        p = rng.dirichlet(np.ones(4)).astype(np.float32)
        probs = node_probabilities(tax, p)
        for node in tax.nodes.values():
            for child in node.children:
                assert probs[child] <= probs[node.taxon_id] + 1e-9


# -- descent --------------------------------------------------------------------


def test_stops_at_genus_when_species_are_split():
    """The behaviour the whole app exists for: report Carabus, do not guess a species."""
    tax = build_beetles()
    p = np.array([0.40, 0.38, 0.12, 0.10], dtype=np.float32)
    r = rollup(tax, p, threshold=0.70)

    assert r.taxon_id == 6
    assert r.rank == "genus"
    assert r.probability == pytest.approx(0.78, abs=1e-6)


def test_descends_to_species_when_confident():
    tax = build_beetles()
    p = np.array([0.85, 0.05, 0.05, 0.05], dtype=np.float32)
    r = rollup(tax, p, threshold=0.70)

    assert r.taxon_id == 7
    assert r.rank == "species"


def test_returns_root_when_nothing_clears_threshold():
    """Kingdom-level ambiguity: an insect-or-plant answer is no answer."""
    tax = build_beetles()
    p = np.array([0.25, 0.20, 0.05, 0.50], dtype=np.float32)
    r = rollup(tax, p, threshold=0.70)

    assert r.taxon_id == tax.root_id
    assert r.is_unidentified


def test_threshold_boundary_is_inclusive():
    """Spec §4.2 — comparison is >=, so exactly-at-threshold descends."""
    tax = build_beetles()
    p = np.array([0.40, 0.30, 0.20, 0.10], dtype=np.float32)
    r = rollup(tax, p, threshold=0.70)

    assert r.taxon_id == 6  # Carabus sits at exactly 0.70
    assert r.rank == "genus"


def test_lower_threshold_never_returns_a_shallower_node():
    tax = build_beetles()
    rng = np.random.default_rng(7)
    for _ in range(100):
        p = rng.dirichlet(np.ones(4)).astype(np.float32)
        deep = rollup(tax, p, threshold=0.50)
        shallow = rollup(tax, p, threshold=0.95)
        # the stricter threshold's answer must be an ancestor-or-self of the looser one
        assert tax.is_ancestor_or_self(shallow.taxon_id, deep.taxon_id)


def test_ties_break_by_lower_taxon_id():
    """Spec §4.2 — deterministic, arbitrary, documented."""
    tax = build_beetles()
    p = np.array([0.5, 0.0, 0.0, 0.5], dtype=np.float32)
    r = rollup(tax, p, threshold=0.50)
    # Animalia (1) and Plantae (20) both sit at 0.5; lower id wins, then descends.
    assert tax.is_ancestor_or_self(1, r.taxon_id)


def test_rejects_threshold_outside_settable_range():
    tax = build_beetles()
    p = np.array([0.25, 0.25, 0.25, 0.25], dtype=np.float32)
    with pytest.raises(ValueError, match="outside the settable range"):
        rollup(tax, p, threshold=0.99)
    with pytest.raises(ValueError, match="outside the settable range"):
        rollup(tax, p, threshold=0.10)


# -- candidates -----------------------------------------------------------------


def test_candidates_are_top_5_leaves_descending():
    tax = build_beetles()
    p = np.array([0.40, 0.38, 0.12, 0.10], dtype=np.float32)
    cands = top_candidates(tax, p)

    assert [c.leaf_index for c in cands] == [0, 1, 2, 3]
    assert [c.taxon_id for c in cands] == [7, 8, 11, 26]
    assert cands[0].probability == pytest.approx(0.40, abs=1e-6)


def test_candidates_are_not_restricted_to_the_returned_subtree():
    """Spec §4.3 — the runner-up genus stays visible even when rollup stops higher."""
    tax = build_beetles()
    p = np.array([0.40, 0.38, 0.12, 0.10], dtype=np.float32)
    r = rollup(tax, p, threshold=0.70)

    assert r.taxon_id == 6  # Carabus
    assert 11 in [c.taxon_id for c in r.candidates]  # Ocypus olens, outside the subtree
    assert 26 in [c.taxon_id for c in r.candidates]  # Bellis perennis, a different kingdom


# -- rollup accuracy metric -----------------------------------------------------


def test_rollup_accuracy_credits_an_honest_ancestor():
    tax = build_beetles()
    p = np.array([0.40, 0.38, 0.12, 0.10], dtype=np.float32)
    r = rollup(tax, p, threshold=0.70)

    assert is_rollup_correct(tax, r, true_leaf_id=7)  # Carabus is an ancestor of granulatus
    assert is_rollup_correct(tax, r, true_leaf_id=8)  # ...and of nemoralis
    assert not is_rollup_correct(tax, r, true_leaf_id=11)  # but not of Ocypus olens


def test_rollup_accuracy_does_not_credit_a_refusal():
    """Otherwise an app that always says "don't know" would score 100%."""
    tax = build_beetles()
    p = np.array([0.25, 0.20, 0.05, 0.50], dtype=np.float32)
    r = rollup(tax, p, threshold=0.70)

    assert r.is_unidentified
    assert not is_rollup_correct(tax, r, true_leaf_id=7)


# -- input validation -----------------------------------------------------------


def test_rejects_wrong_length_probability_vector():
    tax = build_beetles()
    with pytest.raises(ValueError, match="has 3 entries but the taxonomy has 4 leaves"):
        rollup(tax, np.array([0.5, 0.3, 0.2], dtype=np.float32))


def test_rejects_unnormalised_probability_vector():
    tax = build_beetles()
    with pytest.raises(ValueError, match="sums to"):
        rollup(tax, np.array([0.5, 0.5, 0.5, 0.5], dtype=np.float32))


# -- taxonomy invariants (spec §1.1) --------------------------------------------


def test_rejects_two_roots():
    with pytest.raises(TaxonomyError, match="exactly one root"):
        Taxonomy([Taxon(0, None, "root", "A"), Taxon(1, None, "root", "B")])


def test_rejects_internal_node_with_leaf_index():
    with pytest.raises(TaxonomyError, match="leaf_index must be set iff"):
        Taxonomy(
            [
                Taxon(0, None, "root", "Life"),
                Taxon(1, 0, "genus", "Carabus", leaf_index=0),
                Taxon(2, 1, "species", "Carabus granulatus", leaf_index=1),
            ]
        )


def test_rejects_non_contiguous_leaf_indices():
    with pytest.raises(TaxonomyError, match="exactly 0..N_taxa-1"):
        Taxonomy(
            [
                Taxon(0, None, "root", "Life"),
                Taxon(1, 0, "species", "A a", leaf_index=0),
                Taxon(2, 0, "species", "B b", leaf_index=2),
            ]
        )


def test_rejects_child_rank_not_deeper_than_parent():
    with pytest.raises(TaxonomyError, match="is not deeper than"):
        Taxonomy(
            [
                Taxon(0, None, "root", "Life"),
                Taxon(1, 0, "genus", "Carabus"),
                Taxon(2, 1, "family", "Carabidae", leaf_index=0),
            ]
        )


def test_allows_rank_skipping():
    """Real taxonomies have gaps; do not fabricate nodes to fill them."""
    tax = Taxonomy(
        [
            Taxon(0, None, "root", "Life"),
            Taxon(1, 0, "kingdom", "Animalia"),
            Taxon(2, 1, "genus", "Incertae"),  # straight from kingdom to genus
            Taxon(3, 2, "species", "Incertae sedis", leaf_index=0),
        ]
    )
    assert tax.lineage(3) == [0, 1, 2, 3]


def test_species_aggregate_is_a_valid_leaf():
    tax = Taxonomy(
        [
            Taxon(0, None, "root", "Life"),
            Taxon(1, 0, "genus", "Taraxacum"),
            Taxon(2, 1, "species_aggregate", "Taraxacum officinale agg.", leaf_index=0),
        ]
    )
    assert tax.n_taxa == 1
    assert tax.node(2).is_leaf
