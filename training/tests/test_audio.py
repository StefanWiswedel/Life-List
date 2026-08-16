"""Tests for audio detection and identification — shared/taxonomy-spec.md §4A."""

from __future__ import annotations

import pytest

from lifelist_train.audio import (
    apply_geo_prior,
    confusion_set,
    detect,
    identify,
    identify_window,
    lca_rank_depth,
)
from lifelist_train.taxonomy import Taxon, Taxonomy

# leaf indices:
#   0 Phylloscopus collybita   (chiffchaff)
#   1 Phylloscopus trochilus   (willow warbler)
#   2 Erithacus rubecula       (robin)
#   3 Rana temporaria          (common frog)
CHIFFCHAFF, WILLOW, ROBIN, FROG = 100, 101, 110, 200


def build_soundscape() -> Taxonomy:
    return Taxonomy(
        [
            Taxon(0, None, "root", "Life"),
            Taxon(1, 0, "kingdom", "Animalia"),
            Taxon(2, 1, "phylum", "Chordata"),
            # birds
            Taxon(3, 2, "class", "Aves"),
            Taxon(4, 3, "order", "Passeriformes"),
            Taxon(5, 4, "family", "Phylloscopidae"),
            Taxon(6, 5, "genus", "Phylloscopus"),
            Taxon(CHIFFCHAFF, 6, "species", "Phylloscopus collybita", leaf_index=0),
            Taxon(WILLOW, 6, "species", "Phylloscopus trochilus", leaf_index=1),
            Taxon(7, 4, "family", "Muscicapidae"),
            Taxon(8, 7, "genus", "Erithacus"),
            Taxon(ROBIN, 8, "species", "Erithacus rubecula", leaf_index=2),
            # amphibians — the vision model's blind spot, and why audio earns its place
            Taxon(9, 2, "class", "Amphibia"),
            Taxon(10, 9, "order", "Anura"),
            Taxon(11, 10, "family", "Ranidae"),
            Taxon(12, 11, "genus", "Rana"),
            Taxon(FROG, 12, "species", "Rana temporaria", leaf_index=3),
        ]
    )


# -- detection is multi-label ---------------------------------------------------


def test_detection_is_multi_label():
    """Three species singing at once is three detections, not a three-way contest."""
    scores = {CHIFFCHAFF: 0.8, ROBIN: 0.7, FROG: 0.6, WILLOW: 0.05}
    dets = detect(scores, detection_threshold=0.25)

    assert [d.taxon_id for d in dets] == [CHIFFCHAFF, ROBIN, FROG]


def test_detection_respects_threshold():
    scores = {CHIFFCHAFF: 0.30, ROBIN: 0.20}

    assert len(detect(scores, detection_threshold=0.25)) == 1
    assert len(detect(scores, detection_threshold=0.15)) == 2


def test_sigmoid_scores_need_not_sum_to_one():
    """The whole reason §4A exists — this must not raise."""
    scores = {CHIFFCHAFF: 0.9, WILLOW: 0.85, ROBIN: 0.8, FROG: 0.75}
    assert len(detect(scores)) == 4


# -- confusion sets -------------------------------------------------------------


def test_confusion_set_groups_congeners():
    tax = build_soundscape()
    scores = {CHIFFCHAFF: 0.45, WILLOW: 0.40, ROBIN: 0.30}
    det = detect(scores)[0]

    # The robin scores above the margin floor but shares only an order with the
    # warblers, so it is excluded: rolling that pair up would land on Passeriformes,
    # which is a true statement and a useless one.
    assert confusion_set(tax, scores, det, margin=0.5) == (CHIFFCHAFF, WILLOW)


def test_confusion_set_excludes_distant_taxa():
    """A frog and a warbler are two detections, not two candidates for one ID."""
    tax = build_soundscape()
    scores = {CHIFFCHAFF: 0.9, FROG: 0.85}
    det = detect(scores)[0]

    assert confusion_set(tax, scores, det) == (CHIFFCHAFF,)


def test_confusion_set_excludes_low_scorers():
    tax = build_soundscape()
    scores = {CHIFFCHAFF: 0.90, WILLOW: 0.10}
    det = detect(scores)[0]

    assert confusion_set(tax, scores, det, margin=0.5) == (CHIFFCHAFF,)


def test_confusion_set_always_contains_itself():
    tax = build_soundscape()
    scores = {FROG: 0.99}
    det = detect(scores)[0]

    assert confusion_set(tax, scores, det) == (FROG,)


def test_lca_rank_depth():
    tax = build_soundscape()

    assert lca_rank_depth(tax, CHIFFCHAFF, WILLOW) == 6  # genus
    assert lca_rank_depth(tax, CHIFFCHAFF, ROBIN) == 4  # order
    assert lca_rank_depth(tax, CHIFFCHAFF, FROG) == 2  # phylum


# -- identification: the honesty property ---------------------------------------


def test_confusable_congeners_resolve_to_genus():
    """The point of the whole exercise.

    Chiffchaff and willow warbler in a poor recording are genuinely hard. BirdNET
    would report a species at 0.45; we report Phylloscopus.
    """
    tax = build_soundscape()
    scores = {CHIFFCHAFF: 0.45, WILLOW: 0.40}
    det = detect(scores)[0]

    ident = identify(tax, scores, det, threshold=0.70)

    assert ident.result.taxon_id == 6
    assert ident.result.rank == "genus"


def test_clear_winner_resolves_to_species():
    tax = build_soundscape()
    scores = {CHIFFCHAFF: 0.95, WILLOW: 0.10}
    det = detect(scores)[0]

    ident = identify(tax, scores, det, threshold=0.70)

    assert ident.result.taxon_id == CHIFFCHAFF
    assert ident.result.rank == "species"


def test_conditional_distribution_sums_to_one():
    """Renormalisation inside the confusion set (spec §4A.3)."""
    tax = build_soundscape()
    scores = {CHIFFCHAFF: 0.45, WILLOW: 0.40}
    det = detect(scores)[0]

    ident = identify(tax, scores, det, threshold=0.70)
    total = sum(c.probability for c in ident.result.candidates)

    assert total == pytest.approx(1.0, abs=1e-5)


def test_each_detection_is_identified_independently():
    tax = build_soundscape()
    scores = {CHIFFCHAFF: 0.45, WILLOW: 0.40, FROG: 0.9}

    idents = identify_window(tax, scores, threshold=0.70)

    by_taxon = {i.detection.taxon_id: i for i in idents}
    assert by_taxon[FROG].result.taxon_id == FROG  # confident, own confusion set
    assert by_taxon[CHIFFCHAFF].result.rank == "genus"  # ambiguous with its congener


# -- geographic prior -----------------------------------------------------------


def test_geo_prior_reweights_without_masking():
    """Spec §4A.4 — a vagrant must stay loggable."""
    tax = build_soundscape()
    scores = {CHIFFCHAFF: 0.50, WILLOW: 0.48}
    geo = {CHIFFCHAFF: 0.9, WILLOW: 0.01}  # willow warbler out of season
    det = detect(scores)[0]

    ident = identify(tax, scores, det, threshold=0.70, geo=geo)

    assert ident.result.taxon_id == CHIFFCHAFF  # prior broke the tie
    assert WILLOW in ident.confusion_set  # but it was not erased
    assert ident.raw_scores[WILLOW] == pytest.approx(0.48)  # raw score recoverable


def test_geo_weight_zero_disables_the_prior():
    scores = {CHIFFCHAFF: 0.5, WILLOW: 0.5}
    geo = {CHIFFCHAFF: 1.0, WILLOW: 0.0}

    assert apply_geo_prior(scores, geo, weight=0.0) == scores


def test_geo_prior_never_zeroes_a_score():
    """A range likelihood of zero must not make a species unloggable."""
    scores = {WILLOW: 0.9}
    geo = {WILLOW: 0.0}

    assert apply_geo_prior(scores, geo, weight=1.0)[WILLOW] > 0.0


def test_unknown_species_in_geo_model_is_not_erased():
    scores = {FROG: 0.8}

    assert apply_geo_prior(scores, {}, weight=1.0)[FROG] > 0.0


# -- validation -----------------------------------------------------------------


def test_rejects_bad_detection_threshold():
    with pytest.raises(ValueError, match="must be in"):
        detect({CHIFFCHAFF: 0.5}, detection_threshold=0.0)


def test_rejects_bad_margin():
    tax = build_soundscape()
    scores = {CHIFFCHAFF: 0.5}
    det = detect(scores)[0]
    with pytest.raises(ValueError, match="margin must be in"):
        confusion_set(tax, scores, det, margin=1.5)


def test_rejects_non_leaf_detection():
    tax = build_soundscape()
    scores = {6: 0.9}  # the genus Phylloscopus, not a leaf
    det = detect(scores)[0]
    with pytest.raises(ValueError, match="is not a leaf"):
        identify(tax, scores, det, threshold=0.70)
