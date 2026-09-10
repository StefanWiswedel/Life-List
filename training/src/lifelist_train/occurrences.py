"""How often a species is recorded in Denmark — VERIFICATION §72.

The Red List says whether a species is *threatened*, which is not the same question as whether
finding one is notable, and it only covers the species somebody assessed. This is the
complement: GBIF's occurrence count for Denmark, which aggregates museum collections, atlas
surveys, ringing schemes and citizen records rather than one app's users.

**It measures recording effort, not abundance, and the app must say so.** 686,000 blackbirds
against 65,000 small tortoiseshells does not mean ten times as many blackbirds — it means birds
get recorded more. A raw number compared across the tree is therefore meaningless, and a number
labelled "rarity" would be a claim nobody checked.

So the figure that ships is a **position within the species' own family**. "Among the
least-recorded bush-crickets" is a comparison between things recorded by the same people in the
same way; "2,079 records" beside a blackbird is not. It is the same argument that put the
progress figure at family level (§55).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Standing:
    """One species against the others of its family."""

    taxon_id: int
    count: int
    """Danish occurrence records. Zero is a fact, not a gap — see `document`."""

    rank: int
    """1 is the most-recorded of its family."""

    of: int
    """How many species of that family the app knows and has a count for."""

    @property
    def fraction(self) -> float:
        """0.0 for the most recorded of its family, 1.0 for the least."""
        return 0.0 if self.of <= 1 else (self.rank - 1) / (self.of - 1)


def family_of(
    taxon_id: int,
    parents: Mapping[int, int | None],
    ranks: Mapping[int, str],
) -> int | None:
    """The family a taxon sits under, walking up the parent chain."""
    seen: set[int] = set()
    current: int | None = taxon_id
    while current is not None and current not in seen:
        seen.add(current)
        if ranks.get(current) == "family":
            return current
        current = parents.get(current)
    return None


def standings(
    counts: Mapping[int, int],
    families: Mapping[int, int],
) -> dict[int, Standing]:
    """Rank every species inside its own family, most-recorded first.

    Ties share the better rank and consume the positions after it, so three species on 12
    records each are all "4th of 30" rather than 4th, 5th and 6th — they are indistinguishable
    on the evidence and numbering them differently would invent a difference.
    """
    by_family: dict[int, list[int]] = {}
    for taxon_id in counts:
        family = families.get(taxon_id)
        if family is not None:
            by_family.setdefault(family, []).append(taxon_id)

    out: dict[int, Standing] = {}
    for members in by_family.values():
        ordered = sorted(members, key=lambda t: (-counts[t], t))
        previous_count: int | None = None
        rank = 0
        for position, taxon_id in enumerate(ordered, start=1):
            if counts[taxon_id] != previous_count:
                rank = position
                previous_count = counts[taxon_id]
            out[taxon_id] = Standing(taxon_id, counts[taxon_id], rank, len(ordered))
    return out


def document(
    standings_by_taxon: Mapping[int, Standing],
    country: str = "DK",
) -> dict:
    """The shipped artefact.

    A taxon with no Danish records at all is written down with `n: 0` rather than left out.
    "Never recorded in Denmark" is one of the more interesting things this file can say — most
    of the 227 are birds BirdNET can hear and Denmark has never had — and an absent key would
    read as missing data instead.
    """
    return {
        "country": country,
        "source": "GBIF occurrence facets, aggregated over every dataset with Danish records",
        "note": [
            "Recording effort, not abundance. Birds are recorded more than moths by people, ",
            "not by nature, so a count is only comparable within a family — which is what ",
            "`rank` and `of` are for. See VERIFICATION.md section 72.",
        ],
        "taxa": {
            str(taxon_id): {
                "n": standing.count,
                "rank": standing.rank,
                "of": standing.of,
            }
            for taxon_id, standing in sorted(standings_by_taxon.items())
        },
    }


def summarise(standings_by_taxon: Mapping[int, Standing]) -> dict[str, int]:
    counts = [s.count for s in standings_by_taxon.values()]
    return {
        "taxa": len(counts),
        "never recorded in Denmark": sum(1 for c in counts if c == 0),
        "under 100 records": sum(1 for c in counts if 0 < c < 100),
        "over 100,000 records": sum(1 for c in counts if c > 100_000),
    }


def phrase(standing: Standing, family: str) -> str:
    """The sentence, for the report. Kotlin says it its own way on the screen."""
    if standing.count == 0:
        # The cohort comparison adds nothing here: "no Danish records at all" is already the
        # whole fact, and ranking a zero against other zeroes is arithmetic pretending to be
        # information.
        return "no Danish records at all"
    where = "least" if standing.fraction >= 0.5 else "most"
    percent = round((1 - standing.fraction if where == "most" else standing.fraction) * 100)
    return (
        f"{standing.count:,} Danish records — among the {where}-recorded "
        f"{max(percent, 1)}% of the {standing.of} {family} this app knows"
    )


def sorted_by_effort(standings_by_taxon: Mapping[int, Standing]) -> Sequence[Standing]:
    """Least-recorded first — the order a report should read in."""
    return sorted(standings_by_taxon.values(), key=lambda s: (s.count, s.taxon_id))
