"""Tests for name normalisation and synonym resolution.

Every downstream join runs through this module, and its failure mode is silent: a
mismatch drops a taxon, which reads as missing data rather than as a bug.
"""

from __future__ import annotations

from lifelist_train.names import (
    SynonymIndex,
    build_synonym_index,
    normalize,
    parse,
)

# -- normalisation --------------------------------------------------------------


def test_author_citation_is_stripped():
    """Spelled-out and abbreviated authors must both go, or the index fails on a
    subset of records and looks like patchy data rather than a bug."""
    assert normalize("Carabus granulatus Linnaeus, 1758") == "carabus granulatus"
    assert normalize("Bellis perennis L.") == "bellis perennis"
    assert normalize("Ocypus olens Müller 1764") == "ocypus olens"
    assert normalize("Carabus granulatus") == normalize("Carabus granulatus Linnaeus, 1758")


def test_author_stripping_does_not_eat_infraspecific_epithets():
    """The distinction the capitalisation heuristic exists to preserve."""
    assert normalize("Carabus granulatus Linnaeus") == "carabus granulatus"
    assert normalize("Carabus granulatus interstitialis") == "carabus granulatus interstitialis"


def test_shouted_input_is_not_destroyed_by_the_author_heuristic():
    assert normalize("CARABUS GRANULATUS") == "carabus granulatus"


def test_parenthetical_is_removed():
    assert normalize("Ocypus olens (Müller, 1764)") == "ocypus olens"


def test_diacritics_are_folded():
    assert normalize("Ocypus olens (Müller)") == normalize("Ocypus olens (Muller)")


def test_rank_markers_are_dropped():
    assert normalize("Carabus granulatus subsp. interstitialis") == (
        "carabus granulatus interstitialis"
    )
    assert normalize("Salix repens var. argentea") == "salix repens argentea"


def test_aggregate_marker_is_dropped_for_matching():
    assert normalize("Taraxacum officinale agg.") == "taraxacum officinale"


def test_hybrid_keeps_both_parents_and_cannot_collide_with_either():
    """Arter carries records like Grågås × canadagås, so this is live Danish data.

    Flattening a hybrid formula would let it resolve to one parent — a false
    identification wearing a match's clothing.
    """
    key = normalize("Anser anser × Branta canadensis")

    assert key == "anser anser x branta canadensis"
    assert key != normalize("Anser anser")
    assert key != normalize("Branta canadensis")


def test_hybrid_sign_variants_are_equivalent():
    assert normalize("Anser anser × Branta canadensis") == normalize(
        "Anser anser x Branta canadensis"
    )


def test_letter_x_inside_a_name_is_not_a_hybrid_sign():
    """Rumex and Buxus must survive."""
    assert normalize("Rumex acetosa") == "rumex acetosa"
    assert normalize("Buxus sempervirens") == "buxus sempervirens"
    assert not parse("Rumex acetosa").is_hybrid


def test_case_and_whitespace_are_irrelevant():
    assert normalize("  CARABUS   granulatus  ") == normalize("Carabus granulatus")


def test_empty_input_is_safe():
    assert normalize("") == ""
    assert normalize("   ") == ""


# -- parsing --------------------------------------------------------------------


def test_parse_binomial():
    p = parse("Carabus granulatus")

    assert p.genus == "Carabus"
    assert p.specific_epithet == "granulatus"
    assert p.infraspecific_epithet is None
    assert p.binomial == "Carabus granulatus"


def test_parse_trinomial():
    p = parse("Carabus granulatus subsp. interstitialis")

    assert p.binomial == "Carabus granulatus"
    assert p.infraspecific_epithet == "interstitialis"


def test_parse_genus_only():
    p = parse("Carabus")

    assert p.genus == "Carabus"
    assert p.specific_epithet is None
    assert p.binomial is None


def test_parse_detects_hybrid():
    assert parse("Anser anser × Branta canadensis").is_hybrid
    assert not parse("Anser anser").is_hybrid


def test_parse_detects_aggregate():
    assert parse("Taraxacum officinale agg.").is_aggregate
    assert parse("Rubus fruticosus complex").is_aggregate
    assert not parse("Taraxacum officinale").is_aggregate


# -- synonym resolution ---------------------------------------------------------


def test_accepted_name_resolves_to_itself():
    index = build_synonym_index({100: "Carabus granulatus"}, [])

    assert index.resolve("Carabus granulatus") == 100


def test_synonym_resolves_to_accepted():
    index = build_synonym_index(
        {100: "Carabus granulatus"},
        [("Carabus obliquus", 100)],
    )

    assert index.resolve("Carabus obliquus") == 100


def test_resolution_survives_author_citations():
    """iNaturalist and GBIF cite authors differently; the join must not care."""
    index = build_synonym_index({100: "Carabus granulatus Linnaeus, 1758"}, [])

    assert index.resolve("Carabus granulatus") == 100
    assert index.resolve("Carabus granulatus L.") == 100


def test_subspecies_falls_back_to_its_binomial():
    """A subspecies we do not model should land on the species, not vanish."""
    index = build_synonym_index({100: "Carabus granulatus"}, [])

    assert index.resolve("Carabus granulatus subsp. interstitialis") == 100


def test_hybrid_does_not_resolve_to_a_parent():
    """The binomial fallback is exactly the trapdoor a hybrid could fall through.

    `Anser anser × Branta canadensis` parses to a leading binomial of `Anser anser`.
    Resolving to it would file a hybrid goose as a greylag.
    """
    index = build_synonym_index(
        {100: "Anser anser", 101: "Branta canadensis"},
        [],
    )

    assert index.resolve("Anser anser × Branta canadensis") is None
    assert index.resolve("Anser anser") == 100


def test_an_explicitly_indexed_hybrid_still_resolves():
    """Refusing the fallback must not make hybrids unrecordable outright."""
    index = build_synonym_index(
        {100: "Anser anser", 102: "Anser anser × Branta canadensis"},
        [],
    )

    assert index.resolve("Anser anser × Branta canadensis") == 102


def test_unknown_name_resolves_to_none():
    index = build_synonym_index({100: "Carabus granulatus"}, [])

    assert index.resolve("Bellis perennis") is None


def test_empty_name_resolves_to_none():
    index = build_synonym_index({100: "Carabus granulatus"}, [])

    assert index.resolve("") is None


# -- homonyms: the case worth getting right -------------------------------------


def test_homonym_across_kingdoms_is_refused_not_guessed():
    """Prunella is both a bird genus and a mint genus.

    Picking one silently is how a plant ends up filed under Aves. Refusing is the
    only honest answer, and the ambiguity gets reported rather than swallowed.
    """
    index = SynonymIndex()
    index.add("Prunella", 500)  # Prunellidae, the accentors
    index.add("Prunella", 900)  # Lamiaceae, selfheal

    assert index.resolve("Prunella") is None
    assert index.is_ambiguous("Prunella")
    assert "prunella" in index.ambiguous_names


def test_repeated_identical_mapping_is_not_ambiguous():
    index = SynonymIndex()
    index.add("Carabus granulatus", 100)
    index.add("Carabus granulatus Linnaeus, 1758", 100)

    assert index.resolve("Carabus granulatus") == 100
    assert not index.is_ambiguous("Carabus granulatus")


def test_ambiguity_is_reported_for_a_human():
    index = SynonymIndex()
    index.add("Prunella", 500)
    index.add("Prunella", 900)
    index.add("Oenanthe", 501)  # wheatears
    index.add("Oenanthe", 901)  # water-dropworts

    assert index.ambiguous_names == ("oenanthe", "prunella")


def test_ambiguous_binomial_is_not_resolved_by_fallback():
    """The subspecies fallback must not sneak past an ambiguity."""
    index = SynonymIndex()
    index.add("Prunella", 500)
    index.add("Prunella", 900)

    assert index.resolve("Prunella modularis") is None or index.resolve("Prunella") is None


def test_index_length_counts_resolvable_names():
    index = build_synonym_index(
        {100: "Carabus granulatus", 101: "Carabus nemoralis"},
        [("Carabus obliquus", 100)],
    )

    assert len(index) == 3
