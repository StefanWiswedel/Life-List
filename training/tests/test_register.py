"""Denmark's national register, and the three clauses that read it.

The bug these pin is §90: GBIF occurrences filtered to observations still carried the domestic
cat, the aurochs-that-is-a-cow, a horse, a peafowl and several hundred budgerigars, because
every one of them was genuinely seen in Denmark.
"""

from __future__ import annotations

from lifelist_train.register import Register, binomial, genus_of, normalise, read_archive


def register(names=(), genera=()):
    return Register(names=frozenset(names), genera=frozenset(genera))


# -- names -------------------------------------------------------------------------


def test_the_author_is_not_part_of_the_name():
    assert binomial("Natrix natrix (Linnaeus, 1758)") == "Natrix natrix"


def test_a_subspecies_reduces_to_its_species():
    assert binomial("Corvus corone cornix Linnaeus") == "Corvus corone"


def test_a_non_breaking_space_is_a_space():
    # The archive uses them, and a name that does not compare equal to itself vouches for
    # nothing while looking exactly right in a log line.
    assert normalise("Bos\xa0taurus") == "Bos taurus"
    assert genus_of("Bos\xa0taurus") == "Bos"


def test_a_name_with_one_word_survives_being_reduced():
    assert binomial("Animalia") == "Animalia"


# -- the three clauses -------------------------------------------------------------


def test_a_name_the_register_carries_is_vouched_for():
    assert register(names={"Natrix natrix"}).vouches("Natrix natrix")


def test_a_genus_the_register_carries_is_enough():
    # The split that made this clause necessary: the register files the hooded crow under
    # Corvus corone, GBIF gives Corvus cornix its own key, and those never match. Denmark's
    # commonest crow has 636,849 records.
    assert register(genera={"Corvus"}).vouches("Corvus cornix")


def test_a_synonym_the_register_carries_is_enough():
    # And this clause is where the botany lives: ours is Rabelera, Denmark's is Stellaria.
    assert register(names={"Stellaria holostea"}).vouches(
        "Rabelera holostea", ["Stellaria holostea L."]
    )


def test_livestock_is_vouched_for_by_nothing():
    dk = register(names={"Natrix natrix", "Corvus corone"}, genera={"Natrix", "Corvus"})
    for beast in ("Bos taurus", "Ovis aries", "Capra hircus", "Felis catus", "Homo sapiens"):
        assert not dk.vouches(beast, ["Bos primigenius"]), beast


def test_only_the_unvouched_pay_for_a_lookup():
    # 700 requests instead of 14,876. Both cheap clauses are checked first on purpose.
    dk = register(names={"Natrix natrix"}, genera={"Corvus"})
    assert not dk.needs_synonyms("Natrix natrix")
    assert not dk.needs_synonyms("Corvus cornix")
    assert dk.needs_synonyms("Rabelera holostea")


# -- refusing to delete Denmark ----------------------------------------------------


def test_a_stunted_register_is_not_usable():
    # The failure this exists for: a half-finished download vouches for nothing, passes zero
    # species, and the log reads like a correct run against a country with no wildlife.
    assert not register(names={"Natrix natrix"}, genera={"Natrix"}).usable
    assert not Register(names=frozenset(), genera=frozenset()).usable


def test_a_real_sized_register_is_usable():
    big = register(
        names={f"Genus{i} species" for i in range(10_000)},
        genera={f"Genus{i}" for i in range(2_000)},
    )
    assert big.usable


# -- reading the archive -----------------------------------------------------------


def _row(scientific_name):
    # taxonID, taxonID, scientificName, ... up to the 12 columns meta.xml declares.
    return ["id", "id", scientific_name, "", "", "", "", "accepted", "SPECIES", "", "", "Animalia"]


def test_the_archive_gives_both_a_name_and_a_genus():
    dk = read_archive([_row("Natrix natrix (Linnaeus, 1758)")])
    assert "Natrix natrix" in dk.names
    assert "Natrix natrix (Linnaeus, 1758)" in dk.names
    assert "Natrix" in dk.genera


def test_a_short_row_is_skipped_rather_than_crashing_the_run():
    dk = read_archive([["id", "id"], _row("Natrix natrix")])
    assert dk.names == frozenset({"Natrix natrix"})


def test_an_empty_name_vouches_for_nothing():
    dk = read_archive([_row("   "), _row("Natrix natrix")])
    assert "" not in dk.genera


# -- the audit's ordering (§92) ----------------------------------------------------


def test_the_weakest_clause_sorts_first():
    # The render caught this the first time: negating the rank put every by-name row at the
    # top, which is the one thing a page called "where is this weakest" must not do.
    from lifelist_train.audit import Row, weakest_first

    def row(name, clause, records):
        return Row(1, name, None, None, "Insects", records, clause, False, False)

    got = weakest_first([
        row("Common by name", "name", 40_000),
        row("Rare by genus", "genus", 5),
        row("Common by genus", "genus", 9_000),
        row("Rare by synonym", "synonym", 6),
    ])
    assert [r.scientific_name for r in got] == [
        "Rare by genus", "Common by genus", "Rare by synonym", "Common by name",
    ]


def test_a_species_nothing_vouches_for_is_named_rather_than_hidden():
    from lifelist_train.audit import clause_for
    from lifelist_train.register import Register

    empty = Register(names=frozenset(), genera=frozenset())
    assert clause_for(empty, "Bos taurus") is None
