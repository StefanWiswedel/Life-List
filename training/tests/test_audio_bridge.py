"""Finding the iNaturalist taxon behind an audio-only species."""

from __future__ import annotations

import json
from pathlib import Path

from lifelist_train.cli.audio_bridge import already_bridged, leaves_of, pick, resolve


def taxon(name: str, taxon_id: int, rank: str = "species") -> dict:
    return {"id": taxon_id, "name": name, "rank": rank}


# -- picking one, or none --------------------------------------------------------


def test_an_exact_species_match_is_taken():
    assert pick([taxon("Turdus merula", 12727)], "Turdus merula") == 12727


def test_the_generous_neighbours_a_search_returns_are_ignored():
    """iNaturalist's search offers whatever was closest; that generosity is the problem."""
    results = [
        taxon("Turdus merula", 12727),
        taxon("Turdus merula merula", 99998, rank="subspecies"),
        taxon("Turdus philomelos", 12736),
    ]

    assert pick(results, "Turdus merula") == 12727


def test_two_taxa_with_the_same_name_are_refused_not_guessed():
    """A wrong photograph beside a confident identification is what makes somebody trust the
    wrong bird — so the rule is the one that has held since the first synonym table."""
    results = [taxon("Turdus merula", 12727), taxon("Turdus merula", 55555)]

    assert pick(results, "Turdus merula") is None


def test_a_search_that_found_nothing_close_resolves_to_nothing():
    assert pick([taxon("Turdus philomelos", 12736)], "Turdus merula") is None


def test_matching_ignores_case_but_not_spelling():
    assert pick([taxon("turdus merula", 12727)], "Turdus merula") == 12727
    assert pick([taxon("Turdus merulla", 12727)], "Turdus merula") is None


# -- what gets asked for ---------------------------------------------------------


def test_only_the_leaves_the_existing_bridge_misses_are_looked_up(tmp_path: Path):
    taxonomy = tmp_path / "audio_taxonomy.json"
    taxonomy.write_text(
        json.dumps([
            {"taxon_id": 0, "parent_id": None, "rank": "root", "scientific_name": "Life"},
            {"taxon_id": 100, "parent_id": 0, "rank": "species",
             "scientific_name": "Turdus merula", "leaf_index": 0},
            {"taxon_id": 200, "parent_id": 0, "rank": "species",
             "scientific_name": "Pica pica", "leaf_index": 1},
        ]),
        encoding="utf-8",
    )
    bridge = tmp_path / "taxon_bridge.json"
    bridge.write_text(json.dumps({"mapping": {"12727": 100}}), encoding="utf-8")

    leaves = leaves_of(taxonomy)
    covered = already_bridged(bridge)

    assert leaves == {100: "Turdus merula", 200: "Pica pica"}
    assert covered == {100}


def test_a_synthetic_sp_leaf_is_not_looked_up(tmp_path: Path):
    """`Carabus sp.` is a genus wearing a species' clothes; iNaturalist has no such taxon."""
    taxonomy = tmp_path / "t.json"
    taxonomy.write_text(
        json.dumps([
            {"taxon_id": -700, "parent_id": 700, "rank": "species",
             "scientific_name": "Carabus sp.", "leaf_index": 0},
            {"taxon_id": 100, "parent_id": 0, "rank": "species",
             "scientific_name": "Turdus merula", "leaf_index": 1},
        ]),
        encoding="utf-8",
    )

    assert leaves_of(taxonomy) == {100: "Turdus merula"}


def test_every_failure_is_reported_rather_than_dropped():
    asked: list[str] = []

    def search(name: str):
        asked.append(name)
        return [taxon("Turdus merula", 12727)] if name == "Turdus merula" else []

    found, missed = resolve({100: "Turdus merula", 200: "Pica pica"}, search)

    assert found == {100: 12727}
    assert missed == [(200, "Pica pica")]
    assert asked == ["Turdus merula", "Pica pica"]
