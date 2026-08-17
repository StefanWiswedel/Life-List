"""Tests for the BirdNET → GBIF bridge — build plan §3.5.

The failure mode this guards against: a bridge that silently drops classes looks
exactly like a bridge that works.
"""

from __future__ import annotations

from lifelist_train.birdnet import (
    bridge_to_gbif,
    parse_label,
    parse_labels,
    scores_to_taxa,
    unmatched_report_lines,
)
from lifelist_train.names import SynonymIndex, build_synonym_index

CHIFFCHAFF, WILLOW, ROBIN, FROG = 100, 101, 110, 200


def danish_index() -> SynonymIndex:
    return build_synonym_index(
        {
            CHIFFCHAFF: "Phylloscopus collybita",
            WILLOW: "Phylloscopus trochilus",
            ROBIN: "Erithacus rubecula",
            FROG: "Rana temporaria",
        },
        [("Rana fusca", FROG)],
    )


# -- label parsing --------------------------------------------------------------


def test_parse_label_splits_on_underscore():
    label = parse_label(0, "Phylloscopus collybita_Common Chiffchaff")

    assert label.scientific_name == "Phylloscopus collybita"
    assert label.common_name == "Common Chiffchaff"


def test_parse_label_tolerates_underscores_in_the_common_name():
    label = parse_label(0, "Anas platyrhynchos_Mallard_Duck")

    assert label.scientific_name == "Anas platyrhynchos"
    assert label.common_name == "Mallard_Duck"


def test_parse_label_tolerates_a_missing_common_name():
    label = parse_label(0, "Phylloscopus collybita")

    assert label.scientific_name == "Phylloscopus collybita"
    assert label.common_name == ""


def test_blank_lines_keep_their_index():
    """Compacting the list would shift every subsequent index and misattribute
    every detection after the gap."""
    labels = parse_labels(["A a_One", "", "B b_Two"])

    assert [label.index for label in labels] == [0, 1, 2]
    assert labels[2].scientific_name == "B b"


def test_non_event_classes_are_recognised():
    assert parse_label(0, "Human vocal_Human vocal").is_non_event
    assert parse_label(1, "Engine_Engine").is_non_event
    assert parse_label(2, "Environmental_Environmental").is_non_event
    assert not parse_label(3, "Phylloscopus collybita_Common Chiffchaff").is_non_event


# -- bridging -------------------------------------------------------------------


def test_matching_classes_map_to_gbif_keys():
    labels = parse_labels(
        [
            "Phylloscopus collybita_Common Chiffchaff",
            "Erithacus rubecula_European Robin",
        ]
    )
    report = bridge_to_gbif(labels, danish_index())

    assert report.mapping == {0: CHIFFCHAFF, 1: ROBIN}
    assert report.match_rate == 1.0


def test_synonym_in_the_birdnet_label_still_bridges():
    labels = parse_labels(["Rana fusca_Common Frog"])
    report = bridge_to_gbif(labels, danish_index())

    assert report.mapping == {0: FROG}


def test_author_citation_in_the_label_does_not_break_the_join():
    labels = parse_labels(["Erithacus rubecula (Linnaeus, 1758)_European Robin"])
    report = bridge_to_gbif(labels, danish_index())

    assert report.mapping == {0: ROBIN}


def test_non_event_classes_are_excluded_but_not_counted_as_failures():
    labels = parse_labels(
        [
            "Phylloscopus collybita_Common Chiffchaff",
            "Engine_Engine",
            "Human vocal_Human vocal",
        ]
    )
    report = bridge_to_gbif(labels, danish_index())

    assert report.non_event_indices == (1, 2)
    assert report.organism_count == 1
    assert report.match_rate == 1.0


def test_unmatched_classes_are_reported_not_dropped():
    labels = parse_labels(
        [
            "Phylloscopus collybita_Common Chiffchaff",
            "Nonexistent species_Not A Real Bird",
        ]
    )
    report = bridge_to_gbif(labels, danish_index())

    assert report.mapping == {0: CHIFFCHAFF}
    assert report.unmatched == ((1, "Nonexistent species"),)
    assert report.match_rate == 0.5


def test_out_of_scope_is_distinguished_from_unmatched():
    """BirdNET is global and we are not. A bird that exists but is not Danish is a
    different problem from a name that did not resolve, and conflating them would
    bury the real failures in expected noise.
    """
    index = build_synonym_index(
        {CHIFFCHAFF: "Phylloscopus collybita", 999: "Cardinalis cardinalis"},
        [],
    )
    labels = parse_labels(
        [
            "Phylloscopus collybita_Common Chiffchaff",
            "Cardinalis cardinalis_Northern Cardinal",
            "Nonexistent species_Nope",
        ]
    )
    report = bridge_to_gbif(labels, index, in_scope_taxa={CHIFFCHAFF})

    assert report.mapping == {0: CHIFFCHAFF}
    assert report.out_of_scope == ((1, "Cardinalis cardinalis"),)
    assert report.unmatched == ((2, "Nonexistent species"),)


def test_ambiguous_names_are_refused_and_reported():
    index = SynonymIndex()
    index.add("Prunella", 500)
    index.add("Prunella", 900)
    labels = parse_labels(["Prunella_Something"])

    report = bridge_to_gbif(labels, index)

    assert report.mapping == {}
    assert report.ambiguous == ((0, "Prunella"),)


def test_report_summary_mentions_every_bucket():
    labels = parse_labels(
        [
            "Phylloscopus collybita_Common Chiffchaff",
            "Nonexistent species_Nope",
            "Engine_Engine",
        ]
    )
    lines = unmatched_report_lines(bridge_to_gbif(labels, danish_index()))

    assert "mapped" in lines[0]
    assert any("Nonexistent species" in line for line in lines)


# -- score projection -----------------------------------------------------------


def test_scores_project_onto_taxon_keys():
    labels = parse_labels(
        [
            "Phylloscopus collybita_Common Chiffchaff",
            "Erithacus rubecula_European Robin",
        ]
    )
    report = bridge_to_gbif(labels, danish_index())

    assert scores_to_taxa([0.8, 0.3], report) == {CHIFFCHAFF: 0.8, ROBIN: 0.3}


def test_unmapped_classes_are_dropped_from_the_score_vector():
    labels = parse_labels(
        [
            "Phylloscopus collybita_Common Chiffchaff",
            "Nonexistent species_Nope",
        ]
    )
    report = bridge_to_gbif(labels, danish_index())

    assert scores_to_taxa([0.8, 0.99], report) == {CHIFFCHAFF: 0.8}


def test_two_classes_mapping_to_one_taxon_take_the_max_not_the_sum():
    """These are sigmoid confidences, not a partition of probability mass.

    Summing could exceed 1 and would overstate confidence precisely where the
    taxonomy is least settled — a BirdNET split that GBIF does not recognise.
    """
    index = build_synonym_index({CHIFFCHAFF: "Phylloscopus collybita"}, [])
    labels = parse_labels(
        [
            "Phylloscopus collybita_Common Chiffchaff",
            "Phylloscopus collybita subsp. abietinus_Siberian Chiffchaff",
        ]
    )
    report = bridge_to_gbif(labels, index)

    assert len(report.mapping) == 2
    assert scores_to_taxa([0.6, 0.7], report) == {CHIFFCHAFF: 0.7}


def test_short_score_vector_does_not_crash():
    labels = parse_labels(["Phylloscopus collybita_C", "Erithacus rubecula_R"])
    report = bridge_to_gbif(labels, danish_index())

    assert scores_to_taxa([0.8], report) == {CHIFFCHAFF: 0.8}
