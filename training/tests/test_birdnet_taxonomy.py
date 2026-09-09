"""Crossing BirdNET's classes into GBIF — build plan §3.5, VERIFICATION §61."""

from __future__ import annotations

from lifelist_train.birdnet import parse_labels
from lifelist_train.birdnet_taxonomy import (
    class_map,
    report_lines,
    resolve,
    vernaculars,
)
from lifelist_train.gbif import build_taxonomy_nodes
from lifelist_train.taxonomy import Taxonomy


def match_payload(
    name: str,
    key: int,
    rank: str = "SPECIES",
    match_type: str = "EXACT",
    **extra,
) -> dict:
    payload = {
        "usageKey": key,
        "canonicalName": name,
        "scientificName": f"{name} (Linnaeus, 1758)",
        "rank": rank,
        "status": "ACCEPTED",
        "confidence": 99,
        "matchType": match_type,
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
        "genusKey": 2493090,
        "speciesKey": key,
    }
    payload.update(extra)
    return payload


CHIFFCHAFF = match_payload("Phylloscopus collybita", 2493091)
WILLOW = match_payload("Phylloscopus trochilus", 2493099)


def labels(*lines: str):
    return parse_labels(list(lines))


# -- what may be accepted --------------------------------------------------------


def test_an_exact_species_match_becomes_a_leaf():
    found = resolve(
        labels("Phylloscopus collybita_Common Chiffchaff"),
        lambda name: CHIFFCHAFF,
    )

    assert class_map(found) == {0: 2493091}
    assert found.taxa[0].lineage["family"] == 9316


def test_a_synonym_resolves_to_the_accepted_taxon():
    """Storing the retired name would put the same bird in the list twice."""
    payload = match_payload(
        "Parus caeruleus", 2482577, status="SYNONYM", acceptedUsageKey=2482578
    )

    found = resolve(labels("Parus caeruleus_Eurasian Blue Tit"), lambda name: payload)

    assert class_map(found) == {0: 2482578}


# -- and what may not ------------------------------------------------------------


def test_a_higher_rank_match_is_refused_not_coarsened():
    """`Astur gentilis` matches the *genus* Astur at confidence 92 (VERIFICATION §61).

    Accepting it would file every goshawk detection under a genus stub, and from the outside
    that looks exactly like a bridge that works.
    """
    payload = match_payload("Astur", 3242735, rank="GENUS", match_type="HIGHERRANK")

    found = resolve(labels("Astur gentilis_Eurasian Goshawk"), lambda name: payload)

    assert found.taxa == {}
    assert found.higher_rank == ((0, "Astur gentilis"),)


def test_a_fuzzy_match_is_refused():
    payload = match_payload("Phylloscopus collybita", 2493091, match_type="FUZZY")

    found = resolve(labels("Phylloscopus colybita_Chiffchaff"), lambda name: payload)

    assert found.taxa == {}
    assert found.fuzzy == ((0, "Phylloscopus colybita"),)


def test_a_family_level_match_is_not_an_identification():
    payload = match_payload("Phylloscopidae", 9316, rank="FAMILY")

    found = resolve(labels("Phylloscopidae sp._Leaf Warbler"), lambda name: payload)

    assert found.taxa == {}
    assert found.wrong_rank == ((0, "Phylloscopidae sp."),)


def test_nothing_back_is_reported_as_unmatched():
    found = resolve(labels("Nonexistentia ficta_Nothing"), lambda name: None)

    assert found.unmatched == ((0, "Nonexistentia fictia".replace("fictia", "ficta")),)


def test_noise_classes_are_not_failures():
    found = resolve(
        labels("Human vocal_Human vocal", "Engine_Engine", "Bufo bufo_Common Toad"),
        lambda name: match_payload("Bufo bufo", 5217160),
    )

    assert found.non_event == (0, 1)
    assert found.organism_count == 1
    assert found.match_rate == 1.0


# -- aliases: a human decision, recorded once ------------------------------------


def test_an_alias_carries_a_decided_disagreement():
    """`Astur gentilis` is BirdNET's placement; GBIF still says Accipiter. Either is
    defensible, so the choice is written down rather than re-litigated on every run."""
    asked: list[str] = []

    def match(name: str):
        asked.append(name)
        return match_payload("Accipiter gentilis", 2480528)

    found = resolve(
        labels("Astur gentilis_Eurasian Goshawk"),
        match,
        aliases={"Astur gentilis": "Accipiter gentilis"},
    )

    assert asked == ["Accipiter gentilis"]
    assert class_map(found) == {0: 2480528}


# -- the tree that comes out -----------------------------------------------------


def test_the_resolved_classes_build_a_valid_taxonomy():
    """The whole point: a second tree the existing rollup can run over unchanged."""
    found = resolve(
        labels("Phylloscopus collybita_Common Chiffchaff", "Phylloscopus trochilus_Willow Warbler"),
        lambda name: CHIFFCHAFF if "collybita" in name else WILLOW,
    )

    taxonomy = Taxonomy(build_taxonomy_nodes(found.taxa.values()))

    assert taxonomy.n_taxa == 2
    assert taxonomy.node(2493091).rank == "species"
    # ancestors materialised from the lineage GBIF attached to the match
    assert taxonomy.node(taxonomy.node(2493091).parent_id).scientific_name == "Phylloscopus"


def test_two_classes_naming_one_taxon_collapse_to_one_leaf():
    """BirdNET models splits GBIF does not. Two classes, one bird, one leaf."""
    found = resolve(
        labels(
            "Phylloscopus collybita_Common Chiffchaff",
            "Phylloscopus brehmii_Iberian Chiffchaff",
        ),
        lambda name: CHIFFCHAFF,
    )

    assert class_map(found) == {0: 2493091, 1: 2493091}
    assert Taxonomy(build_taxonomy_nodes(found.taxa.values())).n_taxa == 1


def test_the_english_names_come_from_the_labels_not_from_more_requests():
    found = resolve(
        labels("Phylloscopus collybita_Common Chiffchaff"),
        lambda name: CHIFFCHAFF,
    )

    assert vernaculars(labels("Phylloscopus collybita_Common Chiffchaff"), found) == {
        2493091: "Common Chiffchaff"
    }


# -- the report ------------------------------------------------------------------


def test_every_refusal_reaches_the_report_with_its_common_name():
    """A bridge that drops classes quietly looks exactly like one that works."""
    given = labels("Astur gentilis_Eurasian Goshawk")
    payload = match_payload("Astur", 3242735, rank="GENUS", match_type="HIGHERRANK")

    printed = "\n".join(report_lines(resolve(given, lambda name: payload), given))

    assert "Astur gentilis" in printed
    assert "Eurasian Goshawk" in printed
    assert "only above species" in printed


# -- the fetcher -----------------------------------------------------------------


def test_a_dropped_connection_is_retried_not_recorded_as_a_missing_taxon():
    """Three connection resets in one 801-name run put the goshawk in the artefact as
    "no match at all", which once committed is indistinguishable from a name GBIF lacks."""
    from lifelist_train.cli.birdnet import _safe_match

    class Flaky:
        def __init__(self):
            self.calls = 0

        def match(self, name):
            self.calls += 1
            if self.calls < 3:
                raise ConnectionResetError(104, "Connection reset by peer")
            return match_payload("Accipiter gentilis", 2480589)

    client = Flaky()

    assert _safe_match(client, "Accipiter gentilis", attempts=4)["usageKey"] == 2480589
    assert client.calls == 3


def test_a_name_that_never_answers_gives_up_and_says_so():
    from lifelist_train.cli.birdnet import _safe_match

    class Dead:
        calls = 0

        def match(self, name):
            Dead.calls += 1
            raise ConnectionResetError(104, "Connection reset by peer")

    assert _safe_match(Dead(), "Whatever it is", attempts=2) is None
    assert Dead.calls == 2
