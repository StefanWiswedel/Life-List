"""Narrowing by character — spec §5, VERIFICATION.md §95.

The wasp that started it: the camera can name seven of Denmark's eleven Vespidae, and stops at
*Vespula* because the character that separates *vulgaris* from *germanica* is on the clypeus
and is not in a photograph taken from above.
"""

from __future__ import annotations

import numpy as np
import pytest

from lifelist_train.characters import (
    CONTRARY,
    Character,
    Guide,
    Option,
    apply_answer,
    narrow,
    renormalise,
)

VULGARIS, GERMANICA, RUFA = 1311477, 1311527, 1311560

CLYPEUS = Character(
    key="clypeus",
    prompt="What does the face look like?",
    options=(
        Option("One black mark, anchor- or dagger-shaped", (VULGARIS,)),
        Option("Three small black dots", (GERMANICA,)),
    ),
    source="https://en.wikipedia.org/wiki/Vespula_germanica",
    note="The mark can appear broken, especially in males, which makes the two look alike.",
)


def test_the_chosen_option_is_favoured_and_the_other_is_not_deleted():
    # The rule the geo prior earned (§4A.4), and it matters more here. Wikipedia itself says
    # the vulgaris mark "can sometimes appear broken... making it look extremely similar", so
    # somebody answering honestly may still be holding a vulgaris.
    after = apply_answer({VULGARIS: 0.5, GERMANICA: 0.5}, CLYPEUS, "Three small black dots")
    assert after[GERMANICA] > after[VULGARIS]
    assert after[VULGARIS] > 0.0, "an honest answer must not make a species unloggable"


def test_a_species_the_character_says_nothing_about_is_left_alone():
    # The clause that is easy to get wrong. rufa is not mentioned by this character, so the
    # answer is not evidence against it — treating silence as contradiction would quietly
    # delete every candidate no key happens to cover.
    before = {VULGARIS: 0.4, GERMANICA: 0.4, RUFA: 0.2}
    after = apply_answer(before, CLYPEUS, "Three small black dots")
    assert after[RUFA] == pytest.approx(0.2)


def test_two_answers_settle_it_without_ever_reaching_certainty():
    p = {VULGARIS: 0.5, GERMANICA: 0.5}
    for _ in range(2):
        p = renormalise(apply_answer(p, CLYPEUS, "Three small black dots"))
    assert p[GERMANICA] > 0.99
    assert p[VULGARIS] > 0.0


def test_an_unknown_answer_is_refused_rather_than_ignored():
    with pytest.raises(KeyError):
        apply_answer({VULGARIS: 1.0}, CLYPEUS, "yellow antennae")


def test_an_answer_that_rules_out_everything_does_not_divide_by_zero():
    # A real outcome: it means the determination was wrong higher up the tree. It must read as
    # that rather than as a crash.
    only_covered = {VULGARIS: 1.0}
    after = apply_answer(only_covered, CLYPEUS, "Three small black dots")
    assert after[VULGARIS] == pytest.approx(CONTRARY)
    assert renormalise({VULGARIS: 0.0})[VULGARIS] == 0.0


def test_reliability_below_the_default_carries_the_sources_doubt():
    shaky = Character(
        key="shaky",
        prompt="?",
        options=(Option("a", (VULGARIS,), reliability=0.55), Option("b", (GERMANICA,))),
    )
    strong = apply_answer({VULGARIS: 0.5}, CLYPEUS, CLYPEUS.options[0].label)[VULGARIS]
    weak = apply_answer({VULGARIS: 0.5}, shaky, "a")[VULGARIS]
    assert weak < strong


# -- which questions are worth asking ------------------------------------------------


def test_a_character_that_cannot_split_the_candidates_is_not_offered():
    # Asking spends the only thing this interaction has, which is patience. A question whose
    # options all point at species already out of the running changes nothing.
    guide = Guide(taxon_id=1311473, view="the face", characters=(CLYPEUS,))
    assert guide.applicable([VULGARIS, GERMANICA]) == (CLYPEUS,)
    assert guide.applicable([VULGARIS, RUFA]) == ()
    assert guide.applicable([RUFA]) == ()


# -- the dense path the vision head actually uses --------------------------------------


def test_the_dense_update_agrees_with_the_sparse_one():
    index = {VULGARIS: 0, GERMANICA: 1, RUFA: 2}
    dense = np.array([0.4, 0.4, 0.2])
    got = narrow(dense, index, CLYPEUS, "Three small black dots")
    want = renormalise(apply_answer({VULGARIS: 0.4, GERMANICA: 0.4, RUFA: 0.2},
                                    CLYPEUS, "Three small black dots"))
    assert got[0] == pytest.approx(want[VULGARIS])
    assert got[1] == pytest.approx(want[GERMANICA])
    assert got[2] == pytest.approx(want[RUFA])
    assert got.sum() == pytest.approx(1.0)


def test_a_taxon_the_model_does_not_carry_is_skipped_not_crashed():
    # A guide outlives the model that it was written against, exactly as a saved record does.
    index = {VULGARIS: 0}
    got = narrow(np.array([1.0]), index, CLYPEUS, "Three small black dots")
    assert got[0] == pytest.approx(1.0)
