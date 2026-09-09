"""BirdNET's classes as a taxonomy of their own — build plan §3.5, VERIFICATION §61.

`taxonomy.json` is the **vision head's output space**: its leaves carry a contiguous
`leaf_index` into that head, and a bird that never reached twenty photographs has no class
in it. BirdNET can hear 534 western-palearctic species that file has no room for, and an
audio-only leaf would break spec §1.1's contiguity invariant rather than bend it.

So audio gets its own tree, built by the same GBIF machinery. The rollup is written against
*a* taxonomy, not against the vision one, and records store a GBIF taxon id — which both trees
share, so the life list stays one list.

Pure functions over already-fetched records, per CLAUDE.md. The matcher is injected; the CLI
supplies one backed by GBIF.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

from .birdnet import BirdNetLabel
from .gbif import GbifTaxon, parse_rank

#: GBIF `matchType` values we accept. Deliberately only the exact ones.
#:
#: FUZZY is a spelling guess and HIGHERRANK is worse than a guess — it silently answers a
#: different question. `Astur gentilis` (BirdNET's name for the goshawk) matches HIGHERRANK to
#: the *genus* `Astur`, DOUBTFUL, at confidence 92. Accepting that would file every goshawk
#: detection under a genus stub and look, from the outside, exactly like a working bridge.
ACCEPTED_MATCH_TYPES = frozenset({"EXACT"})

#: Ranks a detection may land on. A class that resolves to a family is not an identification.
LEAF_RANKS = frozenset({"species", "species_aggregate", "subspecies"})

LINEAGE_RANKS = ("kingdom", "phylum", "class", "order", "family", "genus", "species")


@dataclass(frozen=True, slots=True)
class Resolution:
    """What crossed from BirdNET into GBIF, and what did not."""

    taxa: dict[int, GbifTaxon] = field(default_factory=dict)
    """BirdNET class index → the accepted GBIF taxon it names."""

    non_event: tuple[int, ...] = ()
    """Noise, human speech, machinery. Never organisms, and never failures either."""

    higher_rank: tuple[tuple[int, str], ...] = ()
    """GBIF knows the genus but not this species under this name — usually a recent split."""

    fuzzy: tuple[tuple[int, str], ...] = ()
    """GBIF offered a spelling guess. Refused; a human should decide."""

    unmatched: tuple[tuple[int, str], ...] = ()
    """Nothing came back at all."""

    wrong_rank: tuple[tuple[int, str], ...] = ()
    """Matched something above species that is not a species — not an identification."""

    @property
    def organism_count(self) -> int:
        return (
            len(self.taxa)
            + len(self.higher_rank)
            + len(self.fuzzy)
            + len(self.unmatched)
            + len(self.wrong_rank)
        )

    @property
    def match_rate(self) -> float:
        return len(self.taxa) / self.organism_count if self.organism_count else 0.0

    @property
    def refused(self) -> tuple[tuple[int, str], ...]:
        """Everything a human could act on, in class order."""
        return tuple(sorted(self.higher_rank + self.fuzzy + self.unmatched + self.wrong_rank))

    def summary(self) -> str:
        return (
            f"BirdNET → GBIF: {len(self.taxa)}/{self.organism_count} classes resolved "
            f"({self.match_rate:.1%}); {len(self.higher_rank)} known only above species, "
            f"{len(self.fuzzy)} fuzzy, {len(self.wrong_rank)} wrong rank, "
            f"{len(self.unmatched)} unmatched, {len(self.non_event)} non-event"
        )


def taxon_from_match(payload: Mapping[str, Any]) -> GbifTaxon | None:
    """Turn one `/species/match` response into a `GbifTaxon`, or refuse it.

    The match endpoint already carries the whole lineage — keys *and* names — so one request
    per class builds the entire tree. No second call per taxon, which at 801 classes is the
    difference between a minute and twenty.
    """
    if str(payload.get("matchType", "")).upper() not in ACCEPTED_MATCH_TYPES:
        return None

    rank = parse_rank(payload.get("rank"))
    if rank not in LEAF_RANKS:
        return None

    # A synonym match names the accepted taxon in `acceptedUsageKey`; follow it rather than
    # storing a name GBIF has already retired.
    key = payload.get("acceptedUsageKey") or payload.get("usageKey")
    if key is None:
        return None

    lineage = {
        rank_name: int(payload[f"{rank_name}Key"])
        for rank_name in LINEAGE_RANKS
        if payload.get(f"{rank_name}Key") is not None
    }
    lineage_names = {
        rank_name: str(payload[rank_name])
        for rank_name in LINEAGE_RANKS
        if payload.get(rank_name)
    }

    return GbifTaxon(
        key=int(key),
        scientific_name=str(payload.get("canonicalName") or payload.get("scientificName") or key),
        rank=rank,
        status=str(payload.get("status") or "ACCEPTED"),
        lineage=lineage,
        lineage_names=lineage_names,
    )


def resolve(
    labels: Sequence[BirdNetLabel],
    match: Callable[[str], Mapping[str, Any] | None],
    aliases: Mapping[str, str] | None = None,
) -> Resolution:
    """Cross every BirdNET class into GBIF, reporting each way it can fail differently.

    The failures are kept apart because they are not the same problem. A name GBIF knows only
    above species is a taxonomic disagreement somebody can resolve with an alias; a name that
    returns nothing is probably a typo or a class we have misread; a fuzzy match is GBIF
    guessing, and this project does not accept guesses about which organism something is.

    ``aliases`` records those human decisions — `{"Astur gentilis": "Accipiter gentilis"}` —
    so a resolved disagreement stays resolved and shows up in a diff rather than in a rerun.
    """
    aliases = aliases or {}
    taxa: dict[int, GbifTaxon] = {}
    non_event: list[int] = []
    higher_rank: list[tuple[int, str]] = []
    fuzzy: list[tuple[int, str]] = []
    unmatched: list[tuple[int, str]] = []
    wrong_rank: list[tuple[int, str]] = []

    for label in labels:
        if not label.scientific_name or label.is_non_event:
            non_event.append(label.index)
            continue

        name = aliases.get(label.scientific_name, label.scientific_name)
        payload = match(name)
        if not payload:
            unmatched.append((label.index, label.scientific_name))
            continue

        match_type = str(payload.get("matchType", "")).upper()
        if match_type == "HIGHERRANK":
            higher_rank.append((label.index, label.scientific_name))
            continue
        if match_type == "FUZZY":
            fuzzy.append((label.index, label.scientific_name))
            continue

        taxon = taxon_from_match(payload)
        if taxon is None:
            if match_type in ACCEPTED_MATCH_TYPES:
                wrong_rank.append((label.index, label.scientific_name))
            else:
                unmatched.append((label.index, label.scientific_name))
            continue

        taxa[label.index] = taxon

    return Resolution(
        taxa=taxa,
        non_event=tuple(non_event),
        higher_rank=tuple(higher_rank),
        fuzzy=tuple(fuzzy),
        unmatched=tuple(unmatched),
        wrong_rank=tuple(wrong_rank),
    )


def class_map(resolution: Resolution) -> dict[int, int]:
    """BirdNET class index → GBIF taxon key, in class order.

    Many-to-one is expected and fine: where BirdNET models a split GBIF does not, two classes
    name one taxon. `birdnet.scores_to_taxa` already takes the maximum rather than the sum for
    exactly that case — these are sigmoid confidences, not a partition of probability mass.
    """
    return {index: taxon.key for index, taxon in sorted(resolution.taxa.items())}


def vernaculars(labels: Iterable[BirdNetLabel], resolution: Resolution) -> dict[int, str]:
    """GBIF key → the English name BirdNET already ships, so the tree needs no extra requests.

    Where two classes name one taxon the lower class index wins, which is deterministic and
    means nothing more than that; the names agree in every case seen so far.
    """
    by_index = {label.index: label for label in labels}
    out: dict[int, str] = {}
    for index, taxon in sorted(resolution.taxa.items()):
        common = by_index[index].common_name if index in by_index else ""
        if common and taxon.key not in out:
            out[taxon.key] = common
    return out


def report_lines(resolution: Resolution, labels: Sequence[BirdNetLabel]) -> list[str]:
    """The refusals, grouped by what a person would do about each. Never dropped silently."""
    lines = [resolution.summary()]
    sections = (
        (
            "Known to GBIF only above species — a recent split or a generic placement GBIF "
            "has not adopted. Fixable with an alias:",
            resolution.higher_rank,
        ),
        ("Fuzzy — GBIF offered a spelling guess, which we refuse:", resolution.fuzzy),
        ("Matched something that is not a species:", resolution.wrong_rank),
        ("No match at all:", resolution.unmatched),
    )
    for heading, entries in sections:
        if not entries:
            continue
        lines.append("")
        lines.append(heading)
        by_index = {label.index: label for label in labels}
        for index, name in entries:
            common = by_index[index].common_name if index in by_index else ""
            lines.append(f"  [{index}] {name}" + (f" — {common}" if common else ""))
    return lines
