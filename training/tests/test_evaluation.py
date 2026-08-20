"""Accuracy broken down by what is being identified.

One headline number has been hiding the answer to the question that keeps coming back from real
use — *how good is it on moths?* A model at 94.7% rollup accuracy can be 97% on birds and 70%
on micro-moths, and only one of those describes the evening someone actually had.
"""

from __future__ import annotations

import re
from pathlib import Path

import numpy as np
import pytest

from lifelist_train.evaluation import (
    by_group,
    format_table,
    labels_for,
    threshold_sweep,
)
from lifelist_train.groups import DEFAULT_GROUPS, UNGROUPED, group_of
from lifelist_train.taxonomy import Taxon, Taxonomy

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def taxonomy() -> Taxonomy:
    """Two birds and two moths, under the real GBIF group keys."""
    return Taxonomy(
        [
            Taxon(0, None, "root", "Life"),
            Taxon(212, 0, "class", "Aves"),
            Taxon(2986, 212, "family", "Anatidae"),
            Taxon(101, 2986, "species", "Anas platyrhynchos", leaf_index=0),
            Taxon(102, 2986, "species", "Anas crecca", leaf_index=1),
            Taxon(216, 0, "class", "Insecta"),
            Taxon(797, 216, "order", "Lepidoptera"),
            Taxon(900, 797, "family", "Geometridae"),
            Taxon(201, 900, "species", "Gymnoscelis rufifasciata", leaf_index=2),
            Taxon(202, 900, "species", "Eupithecia vulgata", leaf_index=3),
        ]
    )


# -- grouping ---------------------------------------------------------------

def test_a_lineage_lands_in_its_group():
    assert group_of([0, 212, 2986, 101]) == "Birds"
    assert group_of([0, 216, 797, 900, 201]) == "Insects"


def test_something_in_no_group_is_other():
    assert group_of([0, 99999]) == UNGROUPED


def test_the_first_matching_group_wins():
    # Insecta before Animalia would be, which is the whole reason the order is fixed.
    assert group_of([0, 216, 797], groups=(("Insects", 216), ("Everything", 0))) == "Insects"


def test_the_group_keys_still_match_the_kotlin_ones(taxonomy):
    """Two copies of a list of magic numbers drift. This is the tripwire.

    Same trick as `tools/gen_golden.py --check` plays on the rollup: the other language's
    source is read and compared, so a change in one place fails in the other.
    """
    source = (ROOT / "core/src/main/kotlin/dk/lifelist/core/LifeList.kt").read_text("utf-8")
    block = source.split("val DEFAULT_GROUPS", 1)[1].split("\n)", 1)[0]
    kotlin = [(name, int(key)) for name, key in re.findall(r'Group\("([^"]+)",\s*(\d+)\)', block)]

    assert kotlin, "could not find DEFAULT_GROUPS in the Kotlin source"
    assert kotlin == list(DEFAULT_GROUPS)


# -- which examples belong to which group -----------------------------------

def test_examples_are_grouped_by_the_true_taxon_not_the_prediction(taxonomy):
    # Grouping by the prediction would let a model that calls every moth a beetle report
    # excellent accuracy on moths, because it would have no moths left to be wrong about.
    assert labels_for(taxonomy, [0, 2, 1, 3]) == ["Birds", "Insects", "Birds", "Insects"]


# -- the table --------------------------------------------------------------

def logits_for(truths: list[int], n_leaves: int = 4, confidence: float = 8.0) -> np.ndarray:
    out = np.zeros((len(truths), n_leaves), dtype=np.float32)
    for row, leaf in enumerate(truths):
        out[row, leaf] = confidence
    return out


def test_a_group_that_is_always_right_scores_perfectly(taxonomy):
    truths = np.array([0, 1, 2, 3])
    rows = by_group(taxonomy, logits_for([0, 1, 2, 3]), truths)

    assert {r.label for r in rows} == {"Birds", "Insects"}
    for row in rows:
        assert row.evaluation.leaf_top1 == 1.0
        assert row.evaluation.rollup_accuracy == 1.0


def test_one_weak_group_does_not_hide_behind_a_strong_one(taxonomy):
    # The whole point. Birds perfect, moths always confused with each other.
    truths = np.array([0, 1, 2, 3])
    logits = logits_for([0, 1, 3, 2])  # the two moths swapped
    rows = {r.label: r for r in by_group(taxonomy, logits, truths)}

    assert rows["Birds"].evaluation.leaf_top1 == 1.0
    assert rows["Insects"].evaluation.leaf_top1 == 0.0


def test_the_rollup_rescues_the_weak_group_rather_than_being_wrong(taxonomy):
    # Two moths in one family, split evenly: the rollup should answer "Geometridae", which is
    # correct. This is the mechanism that makes adding thin classes survivable.
    logits = np.array([[0.0, 0.0, 2.0, 2.0]], dtype=np.float32)
    rows = by_group(taxonomy, logits, np.array([2]))

    assert rows[0].evaluation.leaf_top1 in (0.0, 1.0)  # a coin toss between the two moths
    assert rows[0].evaluation.rollup_accuracy == 1.0, "the family answer is right either way"
    assert rows[0].evaluation.mean_returned_depth < 4


def test_rows_come_back_biggest_first(taxonomy):
    truths = np.array([0, 0, 0, 2])
    rows = by_group(taxonomy, logits_for([0, 0, 0, 2]), truths)

    assert [r.label for r in rows] == ["Birds", "Insects"]


def test_taxa_are_counted_as_well_as_examples(taxonomy):
    rows = {r.label: r for r in by_group(taxonomy, logits_for([0, 0, 1]), np.array([0, 0, 1]))}

    assert rows["Birds"].n == 3
    assert rows["Birds"].taxa == 2


def test_a_tiny_group_is_flagged_rather_than_quietly_reported(taxonomy):
    rows = by_group(taxonomy, logits_for([0]), np.array([0]))

    assert rows[0].n == 1
    assert not rows[0].reliable
    assert "*" in format_table(rows)


def test_a_minimum_can_drop_groups_too_small_to_mean_anything(taxonomy):
    rows = by_group(taxonomy, logits_for([0, 0, 0, 2]), np.array([0, 0, 0, 2]), minimum=3)

    assert [r.label for r in rows] == ["Birds"]


# -- sweeping the threshold -------------------------------------------------

def test_the_sweep_reports_every_threshold_asked_for(taxonomy):
    sweep = threshold_sweep(
        taxonomy, logits_for([0, 1, 2, 3]), np.array([0, 1, 2, 3]), 1.0, [0.5, 0.7, 0.9]
    )

    assert sorted(sweep) == [0.5, 0.7, 0.9]
    assert all(rows for rows in sweep.values())


def test_a_higher_threshold_only_ever_refuses_more(taxonomy):
    """The monotone thing, which is *not* accuracy.

    Raising the threshold turns wrong species into right genera — and eventually into refusals
    at root, which are never credited as correct. So accuracy is not monotone in the threshold;
    it rises and then falls off a cliff into refusal. That is exactly the trade the sweep exists
    to show, and asserting the wrong invariant here would have hidden it.
    """
    logits = np.array([[0.0, 0.0, 2.2, 2.0], [3.0, 0.1, 0.0, 0.0]], dtype=np.float32)
    sweep = threshold_sweep(taxonomy, logits, np.array([3, 0]), 1.0, [0.5, 0.7, 0.95])

    refusals = [sweep[t][0].evaluation.refusal_rate for t in (0.5, 0.7, 0.95)]
    assert refusals == sorted(refusals), "refusal rate must never fall as the bar rises"


# -- the printed table ------------------------------------------------------

def test_the_table_has_a_row_per_group_and_a_header(taxonomy):
    text = format_table(by_group(taxonomy, logits_for([0, 1, 2, 3]), np.array([0, 1, 2, 3])))

    assert "group" in text and "rollup" in text and "ECE" in text
    assert "Birds" in text and "Insects" in text
