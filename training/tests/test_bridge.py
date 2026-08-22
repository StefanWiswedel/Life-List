"""Crossing from iNaturalist taxon ids to GBIF keys.

The two id spaces are unrelated and nothing links them but the scientific name. This is the
step that took three attempts to go from 83% of photographs matched to 97.2%, and it lived in a
throwaway script until it had produced two shipped models.
"""

from __future__ import annotations

from lifelist_train.bridge import (
    BridgeReport,
    build_mapping,
    document,
    genus_keys_by_name,
    load,
    needed_keys,
    restrict,
)
from lifelist_train.names import build_synonym_index

# Two ducks and a beetle genus.
ACCEPTED = {
    9761484: "Anas platyrhynchos",
    8214667: "Anas crecca",
    1036776: "Carabus granulatus",
}
RECORDS = {
    9761484: {
        "scientific_name": "Anas platyrhynchos", "rank": "species", "status": "ACCEPTED",
        "lineage": {"class": 212, "family": 2986, "genus": 2498118},
        "lineage_names": {"class": "Aves", "family": "Anatidae", "genus": "Anas"},
        "vernacular_en": "Mallard", "vernacular_da": "Gråand",
    },
    8214667: {
        "scientific_name": "Anas crecca", "rank": "species", "status": "ACCEPTED",
        "lineage": {"class": 212, "family": 2986, "genus": 2498118},
        "lineage_names": {"class": "Aves", "family": "Anatidae", "genus": "Anas"},
        "vernacular_en": "Teal", "vernacular_da": None,
    },
    1036776: {
        "scientific_name": "Carabus granulatus", "rank": "species", "status": "ACCEPTED",
        "lineage": {"class": 216, "family": 5602, "genus": 1036775},
        "lineage_names": {"class": "Insecta", "family": "Carabidae", "genus": "Carabus"},
        "vernacular_en": None, "vernacular_da": None,
    },
    1036775: {
        "scientific_name": "Carabus", "rank": "genus", "status": "ACCEPTED",
        "lineage": {"class": 216, "family": 5602},
        "lineage_names": {"class": "Insecta", "family": "Carabidae"},
        "vernacular_en": None, "vernacular_da": None,
    },
}


def index():
    return build_synonym_index(ACCEPTED, [("Anas boschas", 9761484)])


def genus_keys():
    return genus_keys_by_name(RECORDS.values())


# -- finding the genus keys -------------------------------------------------

def test_genus_keys_come_from_lineages_not_genus_records():
    # A genus is usually only present as somebody's ancestor.
    assert genus_keys()["anas"] == {2498118}
    assert genus_keys()["carabus"] == {1036775}


def test_a_genus_name_used_by_two_keys_is_kept_as_both():
    keys = genus_keys_by_name([
        {"lineage": {"genus": 1}, "lineage_names": {"genus": "Prunella"}},
        {"lineage": {"genus": 2}, "lineage_names": {"genus": "Prunella"}},
    ])

    assert keys["prunella"] == {1, 2}


# -- the crossing itself ----------------------------------------------------

def test_a_species_crosses_by_name():
    mapping, _, _ = build_mapping([(6930, "Anas platyrhynchos", "species")], index(), genus_keys())

    assert mapping == {6930: 9761484}


def test_a_synonym_crosses_to_the_accepted_taxon():
    mapping, _, _ = build_mapping([(6930, "Anas boschas", "species")], index(), genus_keys())

    assert mapping == {6930: 9761484}


def test_a_genus_becomes_an_indeterminate_leaf_not_a_species():
    # §1.1a: a photograph identified only to Carabus is evidence about the genus. Forcing it
    # onto some species underneath would be inventing data.
    mapping, parents, _ = build_mapping([(50, "Carabus", "genus")], index(), genus_keys())

    assert mapping == {50: -1036775}
    assert parents == {1036775}


def test_a_genus_gbif_has_never_heard_of_is_refused():
    mapping, parents, report = build_mapping([(50, "Nonesuch", "genus")], index(), genus_keys())

    assert mapping == {} and parents == set()
    assert report.genus_ambiguous == 1


def test_a_genus_homonym_is_refused_rather_than_guessed():
    # Prunella is a plant genus and a bird genus.
    mapping, _, report = build_mapping([(50, "Prunella", "genus")], index(), {"prunella": {1, 2}})

    assert mapping == {}
    assert report.genus_ambiguous == 1


def test_an_unmatched_name_is_counted_not_crashed_on():
    _, _, report = build_mapping([(1, "Nothing at all", "species")], index(), genus_keys())

    assert report.unresolved == 1


def test_a_nameless_row_is_counted_separately():
    _, _, report = build_mapping([(1, "", "species")], index(), genus_keys())

    assert report.unknown_id == 1


def test_the_report_adds_up_and_reads_as_a_sentence():
    report = BridgeReport(bridged=90, ambiguous=3, unresolved=5, genus_ambiguous=1, unknown_id=1)

    assert report.attempted == 100
    assert "90/100" in report.summary() and "90.0%" in report.summary()


# -- what the taxonomy builder will need ------------------------------------

def test_needed_keys_takes_the_absolute_value_of_an_indeterminate_id():
    assert needed_keys({1: -1036775, 2: 9761484}, []) == {1036775, 9761484}


def test_ancestors_are_not_listed_because_the_records_carry_their_own_lineage():
    # build_taxonomy_nodes reconstructs Anatidae and Aves from the record's lineage_names,
    # which is why those fields are not optional in the document.
    assert 2986 not in needed_keys({1: 9761484}, [])


# -- the shipped artefact ---------------------------------------------------

def test_the_document_round_trips():
    mapping = {6930: 9761484, 50: -1036775}
    doc = document(mapping, [1036775], RECORDS)
    back, parents, taxa = load(doc)

    assert back == mapping
    assert parents == [1036775]
    assert {t.key for t in taxa} == {9761484, 1036775}
    assert next(t for t in taxa if t.key == 9761484).vernacular_en == "Mallard"


def test_the_document_keeps_lineages_so_ancestors_can_be_rebuilt():
    doc = document({6930: 9761484}, [], RECORDS)
    _, _, taxa = load(doc)

    assert taxa[0].lineage_names["family"] == "Anatidae"
    assert taxa[0].lineage["class"] == 212


def test_a_parent_with_no_record_is_left_out_rather_than_shipped_broken():
    doc = document({6930: 9761484}, [999999], RECORDS)

    assert doc["indeterminate_parents"] == []


def test_the_document_is_sorted_so_a_rebuild_shows_a_readable_diff():
    doc = document({50: -1036775, 6930: 9761484}, [1036775], RECORDS)

    assert [t["key"] for t in doc["taxa"]] == sorted(t["key"] for t in doc["taxa"])


# -- narrowing to a higher threshold ----------------------------------------

def test_restricting_keeps_only_the_taxa_asked_for():
    # The move that makes "embed once at >=20, train heads at 20/30/40/50" work: every higher
    # threshold is a subset of what the bridge already covers.
    doc = document({6930: 9761484, 7000: 8214667, 50: -1036775}, [1036775], RECORDS)
    mapping, parents, taxa = restrict(doc, [6930, 50])

    assert set(mapping) == {6930, 50}
    assert parents == [1036775]
    assert {t.key for t in taxa} == {9761484, 1036775}


def test_a_genus_loses_its_indeterminate_leaf_when_that_leaf_is_dropped():
    # Otherwise the taxonomy grows a leaf with no photographs behind it, and every invariant
    # downstream is quietly wrong.
    doc = document({6930: 9761484, 50: -1036775}, [1036775], RECORDS)
    _, parents, taxa = restrict(doc, [6930])

    assert parents == []
    assert 1036775 not in {t.key for t in taxa}


def test_restricting_to_nothing_is_empty_rather_than_an_error():
    doc = document({6930: 9761484}, [], RECORDS)
    mapping, parents, taxa = restrict(doc, [])

    assert mapping == {} and parents == [] and taxa == []
