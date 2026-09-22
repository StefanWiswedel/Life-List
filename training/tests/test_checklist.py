"""The checklist that decides what "you have not found this yet" is measured against."""

from __future__ import annotations

from lifelist_train.checklist import (
    Species,
    counts,
    document,
    families,
    family_key,
    lineage_of,
    wanted,
)


def record(**kw):
    base = {
        "key": 2480537,
        "status": "ACCEPTED",
        "rank": "species",
        "records": 100,
        "scientific_name": "Buteo buteo",
        "lineage": {"kingdom": 1, "phylum": 44, "class": 212, "order": 7191147,
                    "family": 2877, "genus": 2480517},
        "lineage_names": {"class": "Aves", "family": "Accipitridae"},
    }
    base.update(kw)
    return base


class TestWanted:
    def test_a_well_recorded_accepted_species_is_wanted(self):
        assert wanted(record(), min_records=5)

    def test_gbif_doubting_the_taxon_keeps_it_out(self):
        # 319 of the 29,592 Danish keys above five records come back DOUBTFUL. Listing one as
        # something you have not found yet is asking somebody to look for a name nobody stands
        # behind.
        assert not wanted(record(status="DOUBTFUL"), min_records=5)

    def test_only_species_rank(self):
        # A life list ticks a species. Two subspecies of one bird are one tick, and a genus is
        # not a thing you can fail to have found.
        assert not wanted(record(rank="subspecies"), min_records=5)
        assert not wanted(record(rank="genus"), min_records=5)

    def test_the_threshold_separates_occurring_from_recorded_once(self):
        assert wanted(record(records=5), min_records=5)
        assert not wanted(record(records=4), min_records=5)
        assert not wanted(record(records=0), min_records=5)

    def test_a_missing_count_is_not_an_exception(self):
        assert not wanted(record(records=None), min_records=1)

    def test_a_life_list_is_of_animals_plants_and_fungi(self):
        # Denmark's records hold 1,425 bacteria and 910 diatoms above five records. Real
        # organisms, really recorded, and not things anybody goes out and finds — left in they
        # would be 2,500 species of unreachable denominator making every group look less
        # complete than it is.
        for kingdom in (1, 5, 6):
            assert wanted(record(lineage={"kingdom": kingdom, "family": 1}), min_records=1)
        for kingdom in (3, 4, 7):
            assert not wanted(record(lineage={"kingdom": kingdom, "family": 1}), min_records=1)

    def test_a_record_with_no_kingdom_at_all_is_not_guessed_at(self):
        assert not wanted(record(lineage={"family": 1}), min_records=1)


class TestPlacing:
    def test_the_family_comes_out_of_the_lineage(self):
        assert family_key(record()) == 2877

    def test_a_species_with_no_family_is_none_rather_than_a_guess(self):
        # A couple of hundred algae and fungi are placed no finer than an order. Inventing a
        # family for them is exactly the kind of quiet guess this app does not make.
        assert family_key(record(lineage={"kingdom": 1, "order": 7191147})) is None
        assert family_key(record(lineage={})) is None

    def test_the_lineage_is_coarsest_first(self):
        assert lineage_of(record()) == (1, 44, 212, 7191147, 2877, 2480517)

    def test_a_family_carries_the_lineage_down_to_itself(self):
        # Not further. A family's group must not depend on which of its species was seen first,
        # and a lineage ending at a genus would do exactly that.
        index = families([record(), record(key=9, lineage={**record()["lineage"], "genus": 99})])
        assert index[2877].lineage == (1, 44, 212, 7191147, 2877)
        assert index[2877].scientific_name == "Accipitridae"

    def test_a_family_without_a_name_still_gets_an_entry(self):
        index = families([record(lineage_names={})])
        assert index[2877].scientific_name == "2877"


class TestCounts:
    def species(self, key, family, name):
        return Species(key=key, scientific_name=name, family=family, records=10)

    def test_both_numbers_are_kept(self):
        # "3 of 64 named, 490 in Denmark" says two true things. One number could only say a
        # false one, which is the whole reason this file exists.
        listed = [
            self.species(1, 2877, "a"),
            self.species(2, 2877, "b"),
            self.species(3, 2877, "c"),
        ]
        assert counts(listed, {1, 2}) == {2877: (3, 2)}

    def test_a_species_with_no_family_counts_towards_nothing(self):
        listed = [self.species(1, None, "a"), self.species(2, 2877, "b")]
        assert counts(listed, set()) == {2877: (1, 0)}


class TestDocument:
    def make(self, identifiable=frozenset()):
        listed = [
            Species(2480537, "Buteo buteo", 2877, 764578, "Common Buzzard"),
            Species(2480626, "Accipiter nisus", 2877, 120000, "Eurasian Sparrowhawk"),
            Species(5, "Nowhere known", None, 9),
        ]
        index = families([record()])
        return document(
            listed, index, identifiable,
            country="DK", min_records=5, fetched="2026-09-22",
        )

    def test_it_is_keyed_by_taxon_id_throughout(self):
        # What a record stores and what the app looks up.
        doc = self.make({2480537})
        assert doc["species"]["2480537"]["name"] == "Buteo buteo"
        assert doc["species"]["2480537"]["model"] is True
        assert doc["species"]["2480626"]["model"] is False
        assert doc["families"]["2877"]["species"] == 2
        assert doc["families"]["2877"]["model"] == 1

    def test_a_family_with_nothing_in_it_is_not_written(self):
        doc = document([], families([record()]), set(),
                       country="DK", min_records=5, fetched="x")
        assert doc["families"] == {}

    def test_an_unplaced_species_is_still_listed(self):
        # It can still be found, and still be ticked. It just has no family to count towards.
        doc = self.make()
        assert doc["species"]["5"]["family"] is None

    def test_the_provenance_travels_with_the_data(self):
        doc = self.make()
        assert doc["min_records"] == 5
        assert doc["country"] == "DK"
        assert "GBIF" in doc["source"]
