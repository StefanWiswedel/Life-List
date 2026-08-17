"""BirdNET label parsing and the bridge to GBIF taxon keys — build plan §3.5.

BirdNET labels its classes against its own taxonomy, as `Scientific name_Common Name`.
GBIF taxon keys are the app's spine. Everything BirdNET says has to cross that boundary
before it can reach the life list.

The crossing is not clean. Recent splits, differing generic placements and non-event
classes all fail to match, and the only honest response is to report them. A bridge that
silently drops 8% of its classes looks exactly like a bridge that works.
"""

from __future__ import annotations

from dataclasses import dataclass

from .names import SynonymIndex, normalize

# BirdNET carries classes that are not organisms — noise, human speech, sirens, dogs.
# They must never reach the life list, and they are not failures to report either.
NON_EVENT_MARKERS = (
    "noise",
    "human",
    "engine",
    "siren",
    "fireworks",
    "gun",
    "environmental",
    "power tools",
)


@dataclass(frozen=True, slots=True)
class BirdNetLabel:
    """One class in the BirdNET output vector."""

    index: int
    raw: str
    scientific_name: str
    common_name: str

    @property
    def is_non_event(self) -> bool:
        """True for noise/human/machinery classes rather than organisms."""
        haystack = f"{self.scientific_name} {self.common_name}".lower()
        return any(marker in haystack for marker in NON_EVENT_MARKERS)


@dataclass(frozen=True, slots=True)
class BridgeReport:
    """What crossed, what did not, and why — build plan §3.5."""

    mapping: dict[int, int]
    """BirdNET class index → GBIF accepted taxon key."""

    non_event_indices: tuple[int, ...]
    unmatched: tuple[tuple[int, str], ...]
    ambiguous: tuple[tuple[int, str], ...]
    out_of_scope: tuple[tuple[int, str], ...]
    """Matched a real taxon, but one outside the Danish taxon list."""

    @property
    def organism_count(self) -> int:
        return (
            len(self.mapping)
            + len(self.unmatched)
            + len(self.ambiguous)
            + len(self.out_of_scope)
        )

    @property
    def match_rate(self) -> float:
        return len(self.mapping) / self.organism_count if self.organism_count else 0.0

    def summary(self) -> str:
        return (
            f"BirdNET bridge: {len(self.mapping)}/{self.organism_count} organism classes "
            f"mapped ({self.match_rate:.1%}); "
            f"{len(self.out_of_scope)} out of Danish scope, "
            f"{len(self.unmatched)} unmatched, "
            f"{len(self.ambiguous)} ambiguous, "
            f"{len(self.non_event_indices)} non-event"
        )


def parse_label(index: int, raw: str) -> BirdNetLabel:
    """Parse a `Scientific name_Common Name` label.

    Tolerates a missing common name and extra underscores in the common name, both of
    which occur in the released label files.
    """
    text = (raw or "").strip()
    scientific, separator, common = text.partition("_")
    return BirdNetLabel(
        index=index,
        raw=text,
        scientific_name=scientific.strip(),
        common_name=common.strip() if separator else "",
    )


def parse_labels(lines: list[str]) -> list[BirdNetLabel]:
    """Parse a BirdNET labels file, preserving index order.

    Index order *is* the contract — it maps position in the output vector to class. Blank
    lines are kept as placeholders rather than skipped, because silently compacting the
    list would shift every subsequent index and misattribute every detection after the
    gap.
    """
    return [parse_label(i, line) for i, line in enumerate(lines)]


def bridge_to_gbif(
    labels: list[BirdNetLabel],
    synonyms: SynonymIndex,
    in_scope_taxa: set[int] | None = None,
) -> BridgeReport:
    """Map BirdNET classes onto GBIF accepted taxon keys.

    ``in_scope_taxa`` is the Danish leaf-taxon set. A class that resolves to a real
    taxon outside it is reported separately from one that failed to resolve at all —
    they are different problems. The first is expected (BirdNET is global); the second
    means the name did not match and wants a human eye.
    """
    mapping: dict[int, int] = {}
    non_event: list[int] = []
    unmatched: list[tuple[int, str]] = []
    ambiguous: list[tuple[int, str]] = []
    out_of_scope: list[tuple[int, str]] = []

    for label in labels:
        if not label.scientific_name:
            non_event.append(label.index)
            continue
        if label.is_non_event:
            non_event.append(label.index)
            continue

        if synonyms.is_ambiguous(label.scientific_name):
            ambiguous.append((label.index, label.scientific_name))
            continue

        taxon_id = synonyms.resolve(label.scientific_name)
        if taxon_id is None:
            unmatched.append((label.index, label.scientific_name))
            continue

        if in_scope_taxa is not None and taxon_id not in in_scope_taxa:
            out_of_scope.append((label.index, label.scientific_name))
            continue

        mapping[label.index] = taxon_id

    return BridgeReport(
        mapping=mapping,
        non_event_indices=tuple(non_event),
        unmatched=tuple(unmatched),
        ambiguous=tuple(ambiguous),
        out_of_scope=tuple(out_of_scope),
    )


def scores_to_taxa(
    raw_scores: list[float] | tuple[float, ...],
    report: BridgeReport,
) -> dict[int, float]:
    """Project a BirdNET output vector onto GBIF taxon keys.

    Unmapped classes are dropped — they cannot be represented in the life list — but the
    caller keeps ``report`` so the drop is accounted for rather than invisible.

    Where two BirdNET classes map to the same GBIF taxon (a split BirdNET models and
    GBIF does not), take the maximum rather than the sum. These are sigmoid confidences,
    not a partition of probability mass; summing them could exceed 1 and would overstate
    confidence exactly where the taxonomy is least certain.
    """
    out: dict[int, float] = {}
    for index, taxon_id in report.mapping.items():
        if index >= len(raw_scores):
            continue
        score = float(raw_scores[index])
        out[taxon_id] = max(out.get(taxon_id, 0.0), score)
    return out


def unmatched_report_lines(report: BridgeReport) -> list[str]:
    """Human-readable lines for the stage report. Never dropped silently."""
    lines = [report.summary()]
    if report.unmatched:
        lines.append("")
        lines.append("Unmatched — name did not resolve, needs a human:")
        lines.extend(f"  [{i}] {name}" for i, name in report.unmatched)
    if report.ambiguous:
        lines.append("")
        lines.append("Ambiguous — homonym, refused rather than guessed:")
        lines.extend(f"  [{i}] {name}" for i, name in report.ambiguous)
    return lines


def normalize_for_report(name: str) -> str:
    """Exposed so the stage report can show what the matcher actually compared."""
    return normalize(name)
