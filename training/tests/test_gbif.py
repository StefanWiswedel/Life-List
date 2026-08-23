"""Tests for GBIF parsing and taxonomy assembly — build plan §3, stage 1.

Fixtures are shaped like real GBIF responses. The network client itself is not tested:
GBIF is unreachable from the development sandbox, and a mocked HTTP client mostly tests
the mock. The parsing carries the logic, so the parsing is what gets tested.
"""

from __future__ import annotations

import pytest

from lifelist_train.gbif import (
    GbifTaxon,
    assign_leaf_indices,
    build_taxonomy_nodes,
    collect_synonyms,
    indeterminate_id,
    parse_backbone_record,
    parse_rank,
    pick_vernacular,
)
from lifelist_train.taxonomy import Taxon, Taxonomy


def chiffchaff_record() -> dict:
    return {
        "key": 2493091,
        "nubKey": 2493091,
        "canonicalName": "Phylloscopus collybita",
        "scientificName": "Phylloscopus collybita (Vieillot, 1817)",
        "rank": "SPECIES",
        "taxonomicStatus": "ACCEPTED",
        "kingdom": "Animalia",
        "kingdomKey": 1,
        "phylum": "Chordata",
        "phylumKey": 44,
        "class": "Aves",
        "classKey": 212,
        "order": "Passeriformes",
        "orderKey": 729,
        "family": "Phylloscopidae",
        "familyKey": 9316,
        "genus": "Phylloscopus",
        "genusKey": 2493073,
    }


def willow_warbler_record() -> dict:
    return {
        **chiffchaff_record(),
        "key": 2493112,
        "nubKey": 2493112,
        "canonicalName": "Phylloscopus trochilus",
        "scientificName": "Phylloscopus trochilus (Linnaeus, 1758)",
    }


def daisy_record() -> dict:
    return {
        "key": 3117424,
        "nubKey": 3117424,
        "canonicalName": "Bellis perennis",
        "rank": "SPECIES",
        "taxonomicStatus": "ACCEPTED",
        "kingdom": "Plantae",
        "kingdomKey": 6,
        "phylum": "Tracheophyta",
        "phylumKey": 7707728,
        "class": "Magnoliopsida",
        "classKey": 220,
        "order": "Asterales",
        "orderKey": 414,
        "family": "Asteraceae",
        "familyKey": 3065,
        "genus": "Bellis",
        "genusKey": 3117377,
    }


# -- rank mapping ---------------------------------------------------------------


def test_rank_mapping():
    assert parse_rank("SPECIES") == "species"
    assert parse_rank("genus") == "genus"
    assert parse_rank("VARIETY") == "subspecies"
    assert parse_rank("UNRANKED") is None
    assert parse_rank(None) is None


# -- record parsing -------------------------------------------------------------


def test_parse_backbone_record():
    taxon = parse_backbone_record(chiffchaff_record())

    assert taxon is not None
    assert taxon.key == 2493091
    assert taxon.scientific_name == "Phylloscopus collybita"
    assert taxon.rank == "species"
    assert taxon.is_accepted
    assert taxon.lineage["family"] == 9316
    assert taxon.lineage_names["genus"] == "Phylloscopus"


def test_parse_rejects_records_without_a_usable_rank():
    assert parse_backbone_record({**chiffchaff_record(), "rank": "UNRANKED"}) is None


def test_parse_rejects_records_without_a_name():
    record = chiffchaff_record()
    del record["canonicalName"]
    del record["scientificName"]

    assert parse_backbone_record(record) is None


def test_synonym_is_not_accepted():
    taxon = parse_backbone_record({**chiffchaff_record(), "taxonomicStatus": "SYNONYM"})

    assert taxon is not None
    assert not taxon.is_accepted


# -- taxonomy assembly ----------------------------------------------------------


def test_assembled_nodes_form_a_valid_taxonomy():
    """The real test: whatever comes out must satisfy every spec §1.1 invariant."""
    taxa = [
        parse_backbone_record(r)
        for r in (chiffchaff_record(), willow_warbler_record(), daisy_record())
    ]
    nodes = build_taxonomy_nodes([t for t in taxa if t])

    tax = Taxonomy(nodes)  # raises if any invariant is violated

    assert tax.n_taxa == 3
    assert tax.root_id == 0


def test_shared_lineage_is_materialised_once():
    taxa = [parse_backbone_record(r) for r in (chiffchaff_record(), willow_warbler_record())]
    nodes = build_taxonomy_nodes([t for t in taxa if t])
    tax = Taxonomy(nodes)

    genus_ids = [n.taxon_id for n in nodes if n.rank == "genus"]
    assert genus_ids == [2493073]
    assert set(tax.children(2493073)) == {2493091, 2493112}


def test_lineage_reaches_root_through_every_rank_present():
    taxa = [parse_backbone_record(chiffchaff_record())]
    nodes = build_taxonomy_nodes([t for t in taxa if t])
    tax = Taxonomy(nodes)

    ranks = [tax.node(t).rank for t in tax.lineage(2493091)]
    assert ranks == [
        "root", "kingdom", "phylum", "class", "order", "family", "genus", "species",
    ]


def test_rank_gaps_are_preserved_not_fabricated():
    """A genus whose family GBIF leaves unplaced attaches straight to order."""
    record = chiffchaff_record()
    del record["familyKey"]
    del record["family"]

    nodes = build_taxonomy_nodes([parse_backbone_record(record)])
    tax = Taxonomy(nodes)

    ranks = [tax.node(t).rank for t in tax.lineage(2493091)]
    assert "family" not in ranks
    assert ranks == ["root", "kingdom", "phylum", "class", "order", "genus", "species"]


def test_two_kingdoms_do_not_share_a_parent():
    taxa = [parse_backbone_record(r) for r in (chiffchaff_record(), daisy_record())]
    tax = Taxonomy(build_taxonomy_nodes([t for t in taxa if t]))

    assert set(tax.children(tax.root_id)) == {1, 6}


# -- leaf indices ---------------------------------------------------------------


def test_leaf_indices_are_contiguous_and_ordered_by_taxon_id():
    """Stability matters: a reordering here silently invalidates every exported model."""
    nodes = assign_leaf_indices(
        [
            Taxon(0, None, "root", "Life"),
            Taxon(50, 0, "genus", "Bbb"),
            Taxon(10, 0, "genus", "Aaa"),
            Taxon(60, 50, "species", "Bbb bbb"),
            Taxon(20, 10, "species", "Aaa aaa"),
        ]
    )
    leaves = {n.taxon_id: n.leaf_index for n in nodes if n.leaf_index is not None}

    assert leaves == {20: 0, 60: 1}


def test_internal_nodes_never_get_a_leaf_index():
    nodes = assign_leaf_indices(
        [
            Taxon(0, None, "root", "Life"),
            Taxon(1, 0, "genus", "Carabus"),
            Taxon(2, 1, "species", "Carabus granulatus"),
        ]
    )
    by_id = {n.taxon_id: n for n in nodes}

    assert by_id[0].leaf_index is None
    assert by_id[1].leaf_index is None
    assert by_id[2].leaf_index == 0


def test_assignment_is_stable_across_input_order():
    nodes = [
        Taxon(0, None, "root", "Life"),
        Taxon(10, 0, "genus", "Aaa"),
        Taxon(20, 10, "species", "Aaa aaa"),
        Taxon(50, 0, "genus", "Bbb"),
        Taxon(60, 50, "species", "Bbb bbb"),
    ]
    forward = {n.taxon_id: n.leaf_index for n in assign_leaf_indices(nodes)}
    backward = {n.taxon_id: n.leaf_index for n in assign_leaf_indices(list(reversed(nodes)))}

    assert forward == backward


# -- synonyms -------------------------------------------------------------------


def test_collect_synonyms_extracts_pairs():
    records = [
        {"canonicalName": "Rana fusca", "acceptedKey": 200, "taxonomicStatus": "SYNONYM"},
        {
            "canonicalName": "Phylloscopus rufus",
            "acceptedKey": 2493091,
            "taxonomicStatus": "HETEROTYPIC_SYNONYM",
        },
        {"canonicalName": "Bellis perennis", "key": 300, "taxonomicStatus": "ACCEPTED"},
    ]

    assert collect_synonyms(records) == [("Rana fusca", 200), ("Phylloscopus rufus", 2493091)]


def test_collect_synonyms_skips_records_without_an_accepted_key():
    assert collect_synonyms([{"canonicalName": "Orphan name", "taxonomicStatus": "SYNONYM"}]) == []


# -- vernaculars ----------------------------------------------------------------


def test_preferred_vernacular_wins():
    records = [
        {"language": "eng", "vernacularName": "Chiff-chaff"},
        {"language": "eng", "vernacularName": "Common Chiffchaff", "preferred": True},
    ]

    assert pick_vernacular(records, "eng") == "Common Chiffchaff"


def test_most_repeated_vernacular_wins_without_a_preferred_flag():
    records = [
        {"language": "eng", "vernacularName": "Chiff-chaff"},
        {"language": "eng", "vernacularName": "Common Chiffchaff"},
        {"language": "eng", "vernacularName": "Common Chiffchaff"},
    ]

    assert pick_vernacular(records, "eng") == "Common Chiffchaff"


def test_vernacular_selection_is_deterministic_on_a_tie():
    """A name that changes between runs makes every diff unreadable."""
    records = [
        {"language": "eng", "vernacularName": "Bravo"},
        {"language": "eng", "vernacularName": "Alpha"},
    ]

    assert pick_vernacular(records, "eng") == pick_vernacular(list(reversed(records)), "eng")


def test_other_languages_are_ignored():
    records = [
        {"language": "dan", "vernacularName": "Gransanger"},
        {"language": "eng", "vernacularName": "Common Chiffchaff"},
    ]

    assert pick_vernacular(records, "eng") == "Common Chiffchaff"
    assert pick_vernacular(records, "dan") == "Gransanger"


def test_missing_language_returns_none():
    assert pick_vernacular([{"language": "eng", "vernacularName": "X"}], "dan") is None


# -- indeterminate leaves (spec §1.1a) ------------------------------------------
#
# iNaturalist observers frequently stop at genus, and those observations are evidence.
# Invariant 4 forbids a genus being both an internal node and a leaf, so the genus gets
# a synthetic `<Name> sp.` child instead.


def _species(key, name, genus_key, genus_name, family_key=99, family_name="Fam"):
    return GbifTaxon(
        key=key,
        scientific_name=name,
        rank="species",
        status="ACCEPTED",
        lineage={"family": family_key, "genus": genus_key},
        lineage_names={"family": family_name, "genus": genus_name},
    )


def test_a_genus_with_species_gets_an_indeterminate_child():
    nodes = build_taxonomy_nodes(
        [_species(1, "Carabus granulatus", 10, "Carabus"),
         _species(2, "Carabus nemoralis", 10, "Carabus")],
        indeterminate_parents=[10],
    )
    by_id = {n.taxon_id: n for n in nodes}

    child = by_id[-10]
    assert child.parent_id == 10
    assert child.scientific_name == "Carabus sp."
    assert child.rank == "species"
    assert child.leaf_index is not None


def test_the_genus_itself_stays_an_internal_node():
    nodes = build_taxonomy_nodes(
        [_species(1, "Carabus granulatus", 10, "Carabus")], indeterminate_parents=[10]
    )
    by_id = {n.taxon_id: n for n in nodes}

    assert by_id[10].leaf_index is None, "invariant 4: a node with children is not a leaf"
    assert by_id[1].leaf_index is not None
    assert by_id[-10].leaf_index is not None


def test_a_childless_genus_gets_no_synthetic_child():
    """It is already a leaf. `Carabus sp.` under a childless Carabus says it twice."""
    genus_only = GbifTaxon(
        key=10, scientific_name="Carabus", rank="genus", status="ACCEPTED",
        lineage={"family": 99}, lineage_names={"family": "Fam"},
    )
    nodes = build_taxonomy_nodes([genus_only], indeterminate_parents=[10])
    by_id = {n.taxon_id: n for n in nodes}

    assert -10 not in by_id
    assert by_id[10].leaf_index is not None


def test_an_unknown_parent_key_is_ignored_rather_than_invented():
    nodes = build_taxonomy_nodes(
        [_species(1, "Carabus granulatus", 10, "Carabus")], indeterminate_parents=[12345]
    )

    assert all(n.taxon_id >= 0 for n in nodes)


def test_indeterminate_ids_cannot_collide_with_gbif_keys():
    assert indeterminate_id(1036775) == -1036775
    with pytest.raises(ValueError):
        indeterminate_id(0)


def test_the_tree_still_validates_with_indeterminate_leaves():
    """The real check: Taxonomy asserts all five invariants at construction."""
    nodes = build_taxonomy_nodes(
        [_species(1, "Carabus granulatus", 10, "Carabus"),
         _species(2, "Cepaea nemoralis", 20, "Cepaea", 98, "Helicidae")],
        indeterminate_parents=[10, 20],
    )
    tree = Taxonomy(nodes)

    assert tree.n_taxa == 4  # two species, two indeterminate leaves
    assert tree.node(-10).scientific_name == "Carabus sp."


def test_leaf_indices_stay_contiguous_and_ordered_by_id():
    nodes = build_taxonomy_nodes(
        [_species(1, "Carabus granulatus", 10, "Carabus"),
         _species(2, "Carabus nemoralis", 10, "Carabus")],
        indeterminate_parents=[10],
    )
    leaves = sorted((n for n in nodes if n.leaf_index is not None), key=lambda n: n.leaf_index)

    assert [n.leaf_index for n in leaves] == [0, 1, 2]
    assert [n.taxon_id for n in leaves] == [-10, 1, 2], "negative ids sort first, deterministically"


def test_a_common_name_gets_its_first_letter_and_nothing_else():
    """GBIF is inconsistent: "Mallard" and "sooty mud dweller" come out of the same field."""
    from lifelist_train.gbif import tidy_vernacular

    assert tidy_vernacular("sooty mud dweller") == "Sooty mud dweller"
    assert tidy_vernacular("Mallard") == "Mallard"
    # Title-casing the rest would ruin both of these.
    assert tidy_vernacular("St John's-wort") == "St John's-wort"
    assert tidy_vernacular("daubenton's bat") == "Daubenton's bat"
    assert tidy_vernacular("  Teal  ") == "Teal"
    assert tidy_vernacular(None) is None
    assert tidy_vernacular("   ") is None


def test_a_real_record_fills_in_the_stub_its_lineage_left_behind():
    """Order of arrival is an accident; the common name should not depend on it."""
    from lifelist_train.gbif import GbifTaxon, build_taxonomy_nodes

    species = GbifTaxon(
        key=9761484, scientific_name="Anas platyrhynchos", rank="species", status="ACCEPTED",
        lineage={"family": 2986}, lineage_names={"family": "Anatidae"},
        vernacular_en="Mallard", vernacular_da=None,
    )
    family = GbifTaxon(
        key=2986, scientific_name="Anatidae", rank="family", status="ACCEPTED",
        lineage={}, lineage_names={},
        vernacular_en="ducks, geese and swans", vernacular_da=None,
    )

    for order in ([species, family], [family, species]):
        nodes = {n.taxon_id: n for n in build_taxonomy_nodes(order)}

        assert nodes[2986].vernacular_en == "Ducks, geese and swans"
        assert nodes[9761484].vernacular_en == "Mallard"
        assert nodes[2986].leaf_index is None and nodes[9761484].leaf_index is not None


def test_an_indeterminate_leaf_takes_its_genus_common_name():
    """`Arctium sp.` is a burdock, and saying so is the whole point of §1.1a.

    The shipped-assets test for this passed for weeks by accident: no genus had a common name,
    so there was never a parent name for the child to fail to inherit.
    """
    from lifelist_train.gbif import GbifTaxon, build_taxonomy_nodes

    genus = GbifTaxon(
        key=3097, scientific_name="Arctium", rank="genus", status="ACCEPTED",
        lineage={"family": 3065}, lineage_names={"family": "Asteraceae"},
        vernacular_en="Burdocks", vernacular_da="Burrer",
    )
    species = GbifTaxon(
        key=3098, scientific_name="Arctium minus", rank="species", status="ACCEPTED",
        lineage={"family": 3065, "genus": 3097},
        lineage_names={"family": "Asteraceae", "genus": "Arctium"},
        vernacular_en="Lesser burdock", vernacular_da=None,
    )

    nodes = {n.taxon_id: n for n in build_taxonomy_nodes([genus, species], [3097])}

    assert nodes[-3097].scientific_name == "Arctium sp."
    assert nodes[-3097].vernacular_en == "Burdocks"
    assert nodes[-3097].vernacular_da == "Burrer"
