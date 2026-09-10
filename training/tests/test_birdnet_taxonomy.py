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
        "Carduelis chloris",
        2494642,
        status="SYNONYM",
        acceptedUsageKey=5845582,
        species="Chloris chloris",
        speciesKey=5845582,
    )

    found = resolve(labels("Chloris chloris_European Greenfinch"), lambda name: payload)

    assert class_map(found) == {0: 5845582}
    taxon = found.taxa[0]
    # The accepted *name*, not the matched one. GBIF's match already carries it in `species`.
    assert taxon.scientific_name == "Chloris chloris"
    # And accepted status, so `build_taxonomy_nodes` keeps it. Carrying "SYNONYM" through left
    # the greenfinch out of the tree while its class stayed in the map — a crash the first time
    # one sings (VERIFICATION §64).
    assert taxon.is_accepted


def test_a_subspecies_is_recorded_as_its_species():
    """Otherwise the parent species stops being a leaf, and any class naming it points at an
    internal node — which throws at the first detection of that bird, mid-session."""
    payload = match_payload(
        "Motacilla alba yarrellii",
        6082657,
        rank="SUBSPECIES",
        species="Motacilla alba",
        speciesKey=2490947,
    )

    found = resolve(labels("Motacilla alba yarrellii_Pied Wagtail"), lambda name: payload)

    assert class_map(found) == {0: 2490947}
    assert found.taxa[0].rank == "species"
    assert found.taxa[0].scientific_name == "Motacilla alba"


def test_a_synonym_of_a_subspecies_is_recorded_as_the_species_it_belongs_to():
    """The rank field lies, so `speciesKey` decides.

    `Oenanthe seebohmi` comes back rank SPECIES, status SYNONYM, acceptedUsageKey 5845303 —
    and 5845303 is the *subspecies* `Oenanthe oenanthe seebohmi`. Following the accepted key
    made a leaf keyed 5845303 and named "Oenanthe oenanthe", while the vision taxonomy already
    had that bird as 5231240. A wheatear photographed and a wheatear heard were two entries in
    one life list (VERIFICATION §67).
    """
    payload = match_payload(
        "Oenanthe seebohmi",
        8236049,
        status="SYNONYM",
        acceptedUsageKey=5845303,
        species="Oenanthe oenanthe",
        speciesKey=5231240,
    )

    found = resolve(labels("Oenanthe seebohmi_Atlas Wheatear"), lambda name: payload)

    assert class_map(found) == {0: 5231240}
    assert found.taxa[0].scientific_name == "Oenanthe oenanthe"


def test_two_classes_for_one_gbif_species_share_its_leaf():
    """Which is what BirdNET modelling a split GBIF does not must look like: one bird, one
    entry, and `scores_to_taxa` taking the maximum of the two classes."""
    payloads = {
        "Oenanthe oenanthe": match_payload(
            "Oenanthe oenanthe", 5231240, species="Oenanthe oenanthe", speciesKey=5231240
        ),
        "Oenanthe seebohmi": match_payload(
            "Oenanthe seebohmi", 8236049, status="SYNONYM", acceptedUsageKey=5845303,
            species="Oenanthe oenanthe", speciesKey=5231240,
        ),
    }

    found = resolve(
        labels("Oenanthe oenanthe_Northern Wheatear", "Oenanthe seebohmi_Atlas Wheatear"),
        payloads.get,
    )

    assert class_map(found) == {0: 5231240, 1: 5231240}


def test_every_class_names_a_leaf_of_the_tree_that_gets_built():
    """The property the artefacts are read under: a class map pointing anywhere but a leaf is
    an exception on a phone, in a field, in the middle of a session."""
    payloads = {
        "Motacilla alba": match_payload(
            "Motacilla alba", 2490947, species="Motacilla alba", speciesKey=2490947
        ),
        "Motacilla alba yarrellii": match_payload(
            "Motacilla alba yarrellii", 6082657, rank="SUBSPECIES",
            species="Motacilla alba", speciesKey=2490947,
        ),
        "Phylloscopus collybita": CHIFFCHAFF,
    }
    given = labels(
        "Motacilla alba_White Wagtail",
        "Motacilla alba yarrellii_Pied Wagtail",
        "Phylloscopus collybita_Common Chiffchaff",
    )

    found = resolve(given, payloads.get)
    taxonomy = Taxonomy(build_taxonomy_nodes(found.taxa.values()))

    for index, taxon_id in class_map(found).items():
        assert taxonomy.node(taxon_id).leaf_index is not None, f"class {index} is not a leaf"


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
