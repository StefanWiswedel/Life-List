"""How often a species is recorded in Denmark — and only ever against its own family."""

from __future__ import annotations

from lifelist_train.occurrences import (
    document,
    family_of,
    phrase,
    sorted_by_effort,
    standings,
    summarise,
)

PARENTS = {1: None, 10: 1, 100: 10, 101: 10, 102: 10, 20: 1, 200: 20}
RANKS = {1: "root", 10: "family", 100: "species", 101: "species", 102: "species",
         20: "family", 200: "species"}
FAMILIES = {100: 10, 101: 10, 102: 10, 200: 20}


def test_the_family_is_found_by_walking_up():
    assert family_of(100, PARENTS, RANKS) == 10
    assert family_of(200, PARENTS, RANKS) == 20


def test_a_taxon_with_no_family_above_it_is_reported_as_none():
    """Two of the 3,482 leaves genuinely have no family, and inventing one would be worse."""
    assert family_of(1, PARENTS, RANKS) is None


def test_a_cycle_cannot_hang_the_walk():
    assert family_of(5, {5: 6, 6: 5}, {5: "species", 6: "species"}) is None


# -- ranking, which is the whole point -------------------------------------------


def test_species_are_ranked_inside_their_own_family_only():
    """686,000 blackbirds against 65,000 tortoiseshells is a fact about people, not nature —
    so nothing is ever ranked against the whole tree."""
    ranked = standings({100: 500, 101: 10, 102: 90, 200: 1}, FAMILIES)

    assert (ranked[100].rank, ranked[100].of) == (1, 3)
    assert (ranked[101].rank, ranked[101].of) == (3, 3)
    # Alone in its family, and the app says nothing about position for those.
    assert (ranked[200].rank, ranked[200].of) == (1, 1)


def test_a_tie_shares_the_better_rank():
    """Three species on twelve records each are indistinguishable on the evidence, and
    numbering them 4th, 5th and 6th would invent a difference."""
    ranked = standings({100: 12, 101: 12, 102: 99}, FAMILIES)

    assert ranked[102].rank == 1
    assert ranked[100].rank == 2
    assert ranked[101].rank == 2


def test_the_fraction_runs_from_the_most_recorded_to_the_least():
    ranked = standings({100: 500, 101: 10, 102: 90}, FAMILIES)

    assert ranked[100].fraction == 0.0
    assert ranked[101].fraction == 1.0


def test_a_species_with_no_family_is_left_unranked_rather_than_guessed():
    ranked = standings({100: 5, 999: 5}, FAMILIES)

    assert 999 not in ranked


# -- what gets written and said ---------------------------------------------------


def test_a_zero_is_written_down_rather_than_left_out():
    """"Never recorded in Denmark" is among the more interesting things this file can say, and
    an absent key would read as missing data instead."""
    doc = document(standings({100: 0, 101: 5, 102: 9}, FAMILIES))

    assert doc["taxa"]["100"] == {"n": 0, "rank": 3, "of": 3}


def test_no_records_is_stated_without_a_comparison():
    ranked = standings({100: 0, 101: 0, 102: 9}, FAMILIES)

    assert phrase(ranked[100], "Carabidae") == "no Danish records at all"


def test_the_phrase_names_the_family_it_compares_against():
    ranked = standings({100: 500, 101: 10, 102: 90}, FAMILIES)

    said = phrase(ranked[101], "Carabidae")

    assert "Carabidae" in said
    assert "least-recorded" in said


def test_the_summary_counts_the_ends_of_the_distribution():
    counts = summarise(standings({100: 0, 101: 50, 102: 200_000}, FAMILIES))

    assert counts["never recorded in Denmark"] == 1
    assert counts["under 100 records"] == 1
    assert counts["over 100,000 records"] == 1


def test_the_report_reads_least_recorded_first():
    ranked = standings({100: 500, 101: 10, 102: 90}, FAMILIES)

    assert [s.taxon_id for s in sorted_by_effort(ranked)] == [101, 102, 100]
