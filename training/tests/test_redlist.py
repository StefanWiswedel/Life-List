"""The Danish Red List, matched to the model.

Two things come out of one download: a badge for the 128 species in trouble, and — the reason
it is worth having — a Danish denominator for "12 of 310 Geometridae" that does not shift every
time we retrain.
"""

from __future__ import annotations

from lifelist_train.redlist import (
    MEANING,
    NOTABLE,
    MatchReport,
    document,
    family_totals,
    match,
    normalise,
)

ASSESSMENTS = [
    {"scientificName": "Pelurga comitata", "vernacularName": "Byggegrundsmåler",
     "redlistCategory": "LC", "family": "Geometridae", "order": "Lepidoptera"},
    {"scientificName": "Gymnoscelis rufifasciata", "vernacularName": None,
     "redlistCategory": "NT", "family": "Geometridae", "order": "Lepidoptera"},
    {"scientificName": "Eupithecia insigniata", "vernacularName": "Slåen-dværgmåler",
     "redlistCategory": "EN", "family": "Geometridae", "order": "Lepidoptera"},
    {"scientificName": "Anas platyrhynchos", "vernacularName": "Gråand",
     "redlistCategory": "LC", "family": "Anatidae", "order": "Anseriformes"},
    # In Denmark, not in our model. It still counts towards its family's total.
    {"scientificName": "Eupithecia abietaria", "vernacularName": None,
     "redlistCategory": "VU", "family": "Geometridae", "order": "Lepidoptera"},
]

LEAVES = [
    {"taxon_id": 5148286, "scientific_name": "Pelurga comitata", "vernacular_da": None},
    {"taxon_id": 1874331, "scientific_name": "Gymnoscelis rufifasciata", "vernacular_da": None},
    {"taxon_id": 9761484, "scientific_name": "Anas platyrhynchos", "vernacular_da": "Gråand"},
    {"taxon_id": 4242, "scientific_name": "Nothing assessed", "vernacular_da": None},
]


# -- the denominator, which is the point ------------------------------------

def test_family_totals_count_denmark_not_the_model():
    """"12 of 310 Geometridae" is a fact about the country; "12 of 147" is a fact about us."""
    totals = family_totals(ASSESSMENTS)

    assert totals["Geometridae"] == 4, "including the one the model has never heard of"
    assert totals["Anatidae"] == 1


def test_an_assessment_with_no_family_is_skipped_rather_than_counted_as_blank():
    assert family_totals([{"scientificName": "X", "family": None}]) == {}


# -- the badge --------------------------------------------------------------

def test_categories_come_back_keyed_by_gbif_id():
    categories, _, _ = match(ASSESSMENTS, LEAVES)

    assert categories[5148286] == "LC"
    assert categories[1874331] == "NT"
    assert 4242 not in categories


def test_least_concern_is_not_worth_a_badge():
    """Three quarters of the list is LC. A badge that fires on everything says nothing."""
    assert "LC" not in NOTABLE
    assert set(NOTABLE) == {"RE", "CR", "EN", "VU", "NT"}
    assert all(category in MEANING for category in NOTABLE)


def test_every_category_the_list_uses_has_words_for_it():
    for assessment in ASSESSMENTS:
        assert assessment["redlistCategory"] in MEANING


# -- Danish names -----------------------------------------------------------

def test_a_danish_name_is_taken_only_where_the_taxonomy_has_none():
    """Quietly overwriting GBIF's vernacular with another authority's is a lie that hides."""
    _, danish, _ = match(ASSESSMENTS, LEAVES)

    assert danish[5148286] == "Byggegrundsmåler"
    assert 9761484 not in danish, "the taxonomy already had Gråand"
    assert 1874331 not in danish, "the Red List has no name for it either"


# -- matching ---------------------------------------------------------------

def test_names_match_on_case_and_spacing_and_nothing_cleverer():
    assert normalise("  Pelurga   COMITATA ") == "pelurga comitata"

    categories, _, _ = match(
        [{"scientificName": "pelurga  comitata", "redlistCategory": "VU", "family": "G"}],
        [{"taxon_id": 1, "scientific_name": "Pelurga comitata", "vernacular_da": None}],
    )

    assert categories == {1: "VU"}


def test_the_report_adds_up_and_reads_as_a_sentence():
    _, _, report = match(ASSESSMENTS, LEAVES)

    assert report.leaves == 4 and report.matched == 3
    assert report.notable == 1, "only the near-threatened pug"
    assert report.names_gained == 1
    assert "3/4" in report.summary()


def test_a_report_over_nothing_does_not_divide_by_zero():
    assert "0/0" in MatchReport(0, 0, 0, 0, 0).summary()


# -- the artefact -----------------------------------------------------------

def test_the_document_carries_its_citation_because_the_licence_requires_one():
    doc = document({1: "VU"}, {}, {"Geometridae": 310}, "2026-08-24")

    assert "Rødliste" in doc["source"]
    assert doc["citation"] and doc["url"]
    assert doc["fetched"] == "2026-08-24"


def test_the_document_is_sorted_so_a_rebuild_shows_a_readable_diff():
    doc = document({9: "LC", 1: "VU"}, {9: "b", 1: "a"}, {"B": 2, "A": 1}, "x")

    assert list(doc["categories"]) == ["1", "9"]
    assert list(doc["family_totals"]) == ["A", "B"]
