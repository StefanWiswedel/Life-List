"""Filling in the English names the taxonomy is missing."""

from __future__ import annotations

from lifelist_train.cli.vernaculars import apply, inat_ids, leaf_order, missing, names_from


def node(taxon_id, name, leaf_index=None, vernacular=None):
    return {
        "taxon_id": taxon_id,
        "scientific_name": name,
        "leaf_index": leaf_index,
        "vernacular_en": vernacular,
    }


def test_only_the_empty_fields_are_offered_to_the_second_source():
    """A name GBIF already chose is not up for revision by iNaturalist. This fills gaps; it
    does not arbitrate between two authorities."""
    nodes = [
        node(1, "Turdus merula", 0, "Common Blackbird"),
        node(2, "Pimpla rufipes", 1),
    ]

    assert missing(nodes) == {2: "Pimpla rufipes"}


def test_higher_ranks_are_left_alone_unless_asked_for():
    nodes = [node(1, "Turdidae"), node(2, "Turdus merula", 0)]

    assert missing(nodes) == {2: "Turdus merula"}
    assert set(missing(nodes, leaves_only=False)) == {1, 2}


def test_a_name_iNaturalist_lacks_leaves_the_field_empty():
    """229 species have no English name anywhere. An invented one would be worse than none."""
    found = names_from({55: {"id": 55}}, {2: 55})

    assert found == {}


def test_the_names_are_tidied_the_way_gbif_s_are():
    """iNaturalist's names arrive in whatever casing its users typed. Two capitalisation styles
    in one list reads as a bug in us."""
    records = {55: {"id": 55, "preferred_common_name": "speckled longhorn beetle"}}

    found = names_from(records, {2: 55})

    assert found == {2: "Speckled longhorn beetle"}


def test_applying_a_name_never_overwrites_one_that_is_there():
    nodes = [node(1, "Turdus merula", 0, "Common Blackbird")]

    assert apply(nodes, {1: "Something Else"}) == 0
    assert nodes[0]["vernacular_en"] == "Common Blackbird"


def test_leaf_order_is_read_from_leaf_index_not_from_file_order():
    """The shipped head is a vector whose meaning is positional, so this is the thing that must
    not move — and it is defined by leaf_index, whatever order the file happens to be in."""
    nodes = [node(9, "Third", 2), node(7, "First", 0), node(8, "Second", 1)]

    assert leaf_order(nodes) == [7, 8, 9]


def test_every_crossing_contributes_and_the_first_one_wins(tmp_path):
    import json

    first = tmp_path / "a.json"
    second = tmp_path / "b.json"
    first.write_text(json.dumps({"mapping": {"100": 1}}), encoding="utf-8")
    second.write_text(json.dumps({"mapping": {"200": 1, "300": 2}}), encoding="utf-8")

    assert inat_ids([first, second]) == {1: 100, 2: 300}


def test_a_missing_crossing_file_is_skipped_rather_than_fatal(tmp_path):
    assert inat_ids([tmp_path / "nope.json"]) == {}
