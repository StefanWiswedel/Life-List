"""Choosing a threshold per group, and the one mistake that would make the table a lie."""

from __future__ import annotations

import numpy as np

from lifelist_train.gbif import GbifTaxon, build_taxonomy_nodes
from lifelist_train.rollup import MAX_THRESHOLD, MIN_THRESHOLD, rollup
from lifelist_train.taxonomy import Taxonomy
from lifelist_train.thresholds import (
    CANDIDATES,
    Point,
    choose,
    document,
    format_table,
    predicted_groups,
    sweep,
    table,
)


def points(pairs, n=500):
    return [Point(t, n, a, 0.0, 6.0) for t, a in pairs]


def two_class_taxonomy() -> Taxonomy:
    return Taxonomy(build_taxonomy_nodes([
        GbifTaxon(9761484, "Anas platyrhynchos", "species", "ACCEPTED",
                  {"class": 212}, {"class": "Aves"}, None, None),
        GbifTaxon(1036776, "Carabus granulatus", "species", "ACCEPTED",
                  {"class": 216}, {"class": "Insecta"}, None, None),
    ]))


# -- choosing --------------------------------------------------------------

def test_the_lowest_threshold_that_reaches_the_target_wins():
    """Anything higher pays depth for accuracy that was already bought."""
    chosen = choose(points([(0.5, 0.90), (0.6, 0.95), (0.7, 0.96), (0.8, 0.97)]), 0.95)

    assert chosen.threshold == 0.6
    assert chosen.reached


def test_an_unreachable_target_returns_the_best_on_offer_and_says_so():
    chosen = choose(points([(0.5, 0.80), (0.6, 0.88), (0.7, 0.86)]), 0.95)

    assert chosen.threshold == 0.6 and chosen.accuracy == 0.88
    assert not chosen.reached


def test_a_tie_on_accuracy_prefers_the_deeper_answer():
    chosen = choose(points([(0.6, 0.88), (0.9, 0.88)]), 0.99)

    assert chosen.threshold == 0.6


def test_no_points_is_no_choice_rather_than_a_crash():
    assert choose([], 0.95) is None


# -- which group ------------------------------------------------------------

def test_the_group_is_the_predicted_one_because_truth_is_what_is_unknown():
    """Fitting on true groups and applying to predicted ones measures a world that never
    happens. `evaluation.by_group` splits on truth deliberately, for reporting; this must not."""
    taxonomy = two_class_taxonomy()
    duck = taxonomy.node(9761484).leaf_index
    beetle = taxonomy.node(1036776).leaf_index

    logits = np.zeros((2, taxonomy.n_taxa), dtype=np.float32)
    logits[0, duck] = 10.0      # predicted a bird
    logits[1, beetle] = 10.0    # predicted an insect

    assert predicted_groups(taxonomy, logits) == ["Birds", "Insects"]


def test_the_predicted_group_does_not_move_when_the_threshold_does():
    taxonomy = two_class_taxonomy()
    duck = taxonomy.node(9761484).leaf_index
    logits = np.zeros((1, taxonomy.n_taxa), dtype=np.float32)
    logits[0, duck] = 10.0

    swept = sweep(taxonomy, logits, np.array([duck]), temperature=1.0, candidates=(0.5, 0.9))

    assert list(swept) == ["Birds"]
    assert [p.threshold for p in swept["Birds"]] == [0.5, 0.9]


# -- the table --------------------------------------------------------------

def test_a_group_with_too_few_examples_is_left_out_rather_than_fitted():
    """Forty examples is not evidence, and shipping it would dress noise up as calibration."""
    swept = {"Birds": points([(0.6, 0.96)], n=500), "Mammals": points([(0.6, 1.0)], n=40)}

    assert {c.group for c in table(swept, targets=(0.95,))} == {"Birds"}


def test_the_biggest_group_comes_first():
    swept = {"Mammals": points([(0.6, 0.99)], n=300), "Insects": points([(0.6, 0.99)], n=900)}

    assert [c.group for c in table(swept, targets=(0.95,))] == ["Insects", "Mammals"]


def test_the_document_is_keyed_by_target_because_that_is_what_the_app_asks_for():
    swept = {"Birds": points([(0.62, 0.96)]), "Insects": points([(0.7, 0.96)])}

    assert document(table(swept, targets=(0.95,))) == {
        "0.95": {
            "Birds": {"threshold": 0.62, "accuracy": 0.96, "reached": True, "n": 500},
            "Insects": {"threshold": 0.7, "accuracy": 0.96, "reached": True, "n": 500},
        }
    }


def test_the_document_says_when_the_target_was_not_reached():
    """Otherwise the app promises 95% over a model that manages 80%, and the file cannot tell.

    Four of the nine real groups are in exactly this position, so this is the common case,
    not the corner one.
    """
    swept = {"Mammals": points([(0.6, 0.80), (0.9, 0.82)])}

    entry = document(table(swept, targets=(0.95,)))["0.95"]["Mammals"]

    assert entry["reached"] is False
    assert entry["accuracy"] == 0.82


def test_an_unreachable_target_is_marked_in_the_printed_table():
    printed = format_table(table({"Birds": points([(0.6, 0.80)])}, targets=(0.95,)))

    assert "*" in printed and "out of reach" in printed


def test_the_table_shows_how_many_photographs_each_row_rests_on():
    """The first version printed a blank under the `n` header — `Choice` had no such field.

    It is the column that decides whether "mammals cannot reach 95%" is a real limit of the
    model or a thin slice of test set, so a header with nothing under it is worse than no
    column at all.
    """
    printed = format_table(
        table({"Mammals": points([(0.6, 0.96)], n=1234)}, targets=(0.95,))
    )

    assert "1,234" in printed


# -- the candidate list ----------------------------------------------------

def test_every_candidate_is_a_threshold_the_app_can_be_set_to():
    """The sweep may only recommend a number the app can actually use.

    The first version of this list ran 0.30 to 1.00, and `rollup` refuses both ends. Every
    candidate went through the real function here, so the two cannot drift apart again.
    """
    taxonomy = two_class_taxonomy()
    p = np.array([0.9, 0.1], dtype=np.float32)

    assert CANDIDATES
    assert min(CANDIDATES) == MIN_THRESHOLD
    assert max(CANDIDATES) == MAX_THRESHOLD
    for threshold in CANDIDATES:
        rollup(taxonomy, p, threshold=threshold)


def test_the_sweep_defaults_to_that_list():
    """A default that skipped it would put the bug straight back."""
    taxonomy = two_class_taxonomy()
    logits = np.array([[4.0, 0.0]], dtype=np.float32)

    swept = sweep(taxonomy, logits, np.array([0]), temperature=1.0)

    assert [point.threshold for point in next(iter(swept.values()))] == list(CANDIDATES)
