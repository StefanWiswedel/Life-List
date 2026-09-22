"""Every species recorded in Denmark, not only the ones the model can name.

The app's taxonomy is its *output space*: 3,482 species that cleared twenty iNaturalist
photographs, which is a fact about how often people photograph a thing rather than about what
lives here. That was fine while the app only ever answered questions. It stops being fine the
moment a screen offers to show you what you have **not** found, because then the missing
species have to be missing *from Denmark's list*, not from ours.

The gap is not small. Against the Danish Red List's own family totals the model can name a
median 40% of a family: 64 of 490 weevils, 2 of 228 webcaps. An index built on the model's
vocabulary would show a weevil family as complete at 64 and never mention the other 426.

So the denominator is measured here instead. GBIF's Danish occurrence facets give every species
key ever recorded in the country with a count; above a threshold of independent records, that is
a checklist. **The list you can scroll is the denominator** — a count from one source over a
list from another produces "3 of 490" above 380 rows, and an index whose arithmetic disagrees
with its own contents is worse than one that admits a smaller world.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass

#: Ranks that name something findable. `subspecies` is deliberately absent: a life list ticks a
#: species, and two subspecies of one bird are one tick.
LEAF_RANKS = frozenset({"species"})

#: GBIF's own word for "we are not sure this is a real taxon". Kept out rather than shown.
ACCEPTED = "ACCEPTED"

#: The kingdoms a life list is of. GBIF keys.
#:
#: Denmark's occurrence records include 1,425 bacteria, 910 chromists — mostly diatoms — and
#: 149 protozoa above five records. They are real records of real organisms and they do not
#: belong on a list of things you went out and found: nobody ticks a *Bacillus*, and a diatom
#: is not identifiable from a phone. Left in, they would be 2,500 species of unreachable
#: denominator, quietly making every group look less complete than it is.
LIVING_KINGDOMS: frozenset[int] = frozenset({
    1,  # Animalia
    5,  # Fungi
    6,  # Plantae
})


@dataclass(frozen=True, slots=True)
class Species:
    """One line of the checklist."""

    key: int
    scientific_name: str
    family: int | None
    records: int
    """Danish occurrence records. Recording effort, not abundance — see §72."""

    vernacular_en: str | None = None
    vernacular_da: str | None = None

    @property
    def placed(self) -> bool:
        return self.family is not None


@dataclass(frozen=True, slots=True)
class Family:
    """A family, and the lineage the app needs to put it in a group."""

    key: int
    scientific_name: str
    lineage: tuple[int, ...]
    """Ancestor keys, kingdom first. The app tests its own group keys against this, so the
    grouping has exactly one implementation and it is the one already shipped."""

    vernacular_en: str | None = None


def wanted(
    record: Mapping[str, object],
    min_records: int,
    kingdoms: frozenset[int] = LIVING_KINGDOMS,
) -> bool:
    """Does this fetched record belong on the checklist?

    Four filters, each for its own reason. `DOUBTFUL` is GBIF saying it does not believe the
    taxon; a rank other than species is a genus or a subspecies, neither of which is a tick;
    the record threshold is what separates "occurs in Denmark" from "somebody once typed this
    name into a database"; and the kingdom is what separates a life list from a census.
    """
    if record.get("status") != ACCEPTED:
        return False
    if record.get("rank") not in LEAF_RANKS:
        return False
    if int(record.get("records") or 0) < min_records:
        return False
    lineage = record.get("lineage") or {}
    kingdom = lineage.get("kingdom") if isinstance(lineage, Mapping) else None
    return kingdom is not None and int(kingdom) in kingdoms


def family_key(record: Mapping[str, object]) -> int | None:
    """The family a record sits in, or None.

    Genuinely absent for a couple of hundred species — some algae and fungi are placed no
    finer than an order — and those are counted and reported rather than quietly dropped.
    """
    lineage = record.get("lineage") or {}
    key = lineage.get("family") if isinstance(lineage, Mapping) else None
    return int(key) if key is not None else None


def lineage_of(record: Mapping[str, object]) -> tuple[int, ...]:
    """Ancestor keys, coarsest first, for the grouping the app already does."""
    lineage = record.get("lineage") or {}
    if not isinstance(lineage, Mapping):
        return ()
    order = ("kingdom", "phylum", "class", "order", "family", "genus")
    return tuple(int(lineage[rank]) for rank in order if lineage.get(rank) is not None)


def families(records: Iterable[Mapping[str, object]]) -> dict[int, Family]:
    """Every family the kept records mention, with the lineage they share."""
    out: dict[int, Family] = {}
    for record in records:
        key = family_key(record)
        if key is None or key in out:
            continue
        names = record.get("lineage_names") or {}
        name = names.get("family") if isinstance(names, Mapping) else None
        # Cut the lineage at the family: a family's group cannot depend on which of its
        # species happened to be seen first.
        lineage = lineage_of(record)
        if key in lineage:
            lineage = lineage[: lineage.index(key) + 1]
        out[key] = Family(key=key, scientific_name=str(name or key), lineage=lineage)
    return out


def counts(
    species: Sequence[Species],
    identifiable: frozenset[int] | set[int],
) -> dict[int, tuple[int, int]]:
    """Per family: how many species Denmark has, and how many the model can name.

    Both numbers ship. The first is the denominator the index counts against; the second is
    what the app says about itself, and keeping them apart is the whole point — "3 of 64 named,
    490 in Denmark" says two true things where one number could only say a false one.
    """
    out: dict[int, list[int]] = {}
    for one in species:
        if one.family is None:
            continue
        tally = out.setdefault(one.family, [0, 0])
        tally[0] += 1
        if one.key in identifiable:
            tally[1] += 1
    return {key: (total, known) for key, (total, known) in out.items()}


def document(
    species: Sequence[Species],
    family_index: Mapping[int, Family],
    identifiable: frozenset[int] | set[int],
    *,
    country: str,
    min_records: int,
    fetched: str,
) -> dict:
    """The shipped asset.

    Keyed by taxon id throughout, because that is what a record stores and what the app looks
    up. Families carry the lineage; species carry only their family, so a species' group is a
    fact about its family and cannot disagree with its neighbours'.
    """
    per_family = counts(species, identifiable)
    return {
        "country": country,
        "source": "GBIF occurrence facets and backbone, https://www.gbif.org",
        "min_records": min_records,
        "fetched": fetched,
        "note": [
            "Species recorded in this country at least min_records times. The denominator ",
            "for 'what you have not found yet' — see VERIFICATION.md section 81. `model` ",
            "marks the ones the bundled model can identify; the rest are ticked by hand.",
        ],
        "families": {
            str(key): {
                "name": family.scientific_name,
                "vernacular_en": family.vernacular_en,
                "lineage": list(family.lineage),
                "species": per_family.get(key, (0, 0))[0],
                "model": per_family.get(key, (0, 0))[1],
            }
            for key, family in sorted(family_index.items())
            if per_family.get(key, (0, 0))[0] > 0
        },
        "species": {
            str(one.key): {
                "name": one.scientific_name,
                "vernacular_en": one.vernacular_en,
                "family": one.family,
                "records": one.records,
                "model": one.key in identifiable,
            }
            for one in sorted(species, key=lambda s: s.scientific_name)
        },
    }
