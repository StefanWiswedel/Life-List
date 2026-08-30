"""One dial is serving very different instruments.

The threshold decides how sure the rollup has to be before it commits to a rank. It is a single
number for the whole model, and the per-group table (§41, §44) says that is wrong: birds reach
91.7% rollup accuracy with an expected calibration error of 0.006, while mammals sit at 86.0%
with 0.057 on a tenth of the examples. The same 0.70 buys very different odds depending on what
you photographed.

**The framing worth reaching for is that the user is choosing an accuracy, not a probability.**
"I want to be right 95% of the time" is a sentence somebody can hold an opinion about. "I want
the summed probability to clear 0.70" is not, and it is what the app currently asks.

So: for each group, and each accuracy a person might ask for, find the threshold that delivers
it. The table is small — nine groups by a few targets — and it turns the dial into a promise.

**The group has to be the predicted one, not the true one.** `evaluation.by_group` splits on the
true taxon, which is right for reporting: it answers "how good are we at birds". It is wrong for
choosing a threshold, because at the moment of choosing, the truth is what is unknown. A table
fitted on true groups and applied to predicted ones would be measured under conditions that
never occur, and would read as better than it is.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

import numpy as np

from .groups import DEFAULT_GROUPS, group_of
from .head import evaluate_many
from .rollup import MAX_THRESHOLD, MIN_THRESHOLD
from .taxonomy import Taxonomy


def _candidates(step: float = 0.02) -> tuple[float, ...]:
    """Every threshold the app can actually be set to, and no others.

    The sweep is only allowed to recommend a number the app can be set to, so the candidate
    list is derived from the settable range rather than written out beside it. An earlier
    version started at 0.30 and ran to 1.00; both ends are outside the range and `rollup`
    refuses them, which cost four minutes of somebody's laptop to discover.
    """
    out, threshold = [], MIN_THRESHOLD
    while threshold < MAX_THRESHOLD:
        out.append(round(threshold, 2))
        threshold += step
    out.append(MAX_THRESHOLD)
    return tuple(out)


#: Thresholds to try: the settable range, fine enough that the chosen number is not a rounding.
CANDIDATES: tuple[float, ...] = _candidates()

#: What a person might ask for. Not a slider of probabilities: a promise about being right.
TARGETS: tuple[float, ...] = (0.90, 0.95, 0.98)


@dataclass(frozen=True, slots=True)
class Point:
    """One group at one threshold."""

    threshold: float
    n: int
    accuracy: float
    refusal_rate: float
    mean_depth: float


@dataclass(frozen=True, slots=True)
class Choice:
    """The threshold chosen for a group, and what it costs."""

    group: str
    n: int
    target: float
    threshold: float
    accuracy: float
    refusal_rate: float
    mean_depth: float
    reached: bool

    def summary(self) -> str:
        note = "" if self.reached else "  (best available — the target is out of reach)"
        return (
            f"{self.group:<12} target {self.target:.0%} -> threshold {self.threshold:.2f}  "
            f"accuracy {self.accuracy:.1%}  refused {self.refusal_rate:.1%}  "
            f"depth {self.mean_depth:.2f}{note}"
        )


def predicted_groups(
    taxonomy: Taxonomy,
    logits: np.ndarray,
    groups: Sequence[tuple[str, int]] = DEFAULT_GROUPS,
) -> list[str]:
    """The group of each photograph's top-1 leaf.

    Deliberately independent of the threshold: the top-1 class does not move when the threshold
    does, so the group a photograph belongs to is decided once and the sweep does not chase its
    own tail.
    """
    top = logits.argmax(axis=1)
    return [group_of(taxonomy.lineage(taxonomy.leaf_id(int(i))), groups) for i in top]


def sweep(
    taxonomy: Taxonomy,
    logits: np.ndarray,
    true_leaf_indices: np.ndarray,
    temperature: float,
    groups: Sequence[tuple[str, int]] = DEFAULT_GROUPS,
    candidates: Sequence[float] = CANDIDATES,
) -> dict[str, list[Point]]:
    """Accuracy against threshold, per predicted group."""
    labels = np.array(predicted_groups(taxonomy, logits, groups))
    out: dict[str, list[Point]] = {}
    for label in dict.fromkeys(labels.tolist()):
        mask = labels == label
        block, truth = logits[mask], true_leaf_indices[mask]
        # All the thresholds in one pass: the softmax and the subtree sums do not depend on
        # the threshold, and paying for them once per candidate is what made this an
        # overnight job.
        scored = evaluate_many(
            taxonomy, block, truth, temperature=temperature, thresholds=candidates
        )
        out[label] = [
            Point(
                threshold=float(threshold),
                n=result.n,
                accuracy=result.rollup_accuracy,
                refusal_rate=result.refusal_rate,
                mean_depth=result.mean_returned_depth,
            )
            for threshold, result in scored.items()
        ]
    return out


def choose(points: Sequence[Point], target: float) -> Choice | None:
    """The **lowest** threshold that reaches the target, or the best on offer.

    Lowest, not highest: every threshold above it also reaches the target and each one answers
    a little less deeply, so anything higher pays depth for accuracy that was already bought.
    That is the whole trade this app exists to make well.
    """
    if not points:
        return None
    reaching = [p for p in points if p.accuracy >= target]
    best = min(reaching, key=lambda p: p.threshold) if reaching else max(
        points, key=lambda p: (p.accuracy, -p.threshold)
    )
    return Choice(
        group="",
        n=best.n,
        target=target,
        threshold=best.threshold,
        accuracy=best.accuracy,
        refusal_rate=best.refusal_rate,
        mean_depth=best.mean_depth,
        reached=bool(reaching),
    )


def table(
    swept: Mapping[str, Sequence[Point]],
    targets: Sequence[float] = TARGETS,
    minimum: int = 100,
) -> list[Choice]:
    """A choice per group per target, biggest group first.

    Groups with fewer than `minimum` test photographs are left out rather than fitted: a
    threshold from forty examples is a number with no evidence under it, and shipping it would
    dress up noise as calibration. Those groups fall back to the global threshold.
    """
    ordered = sorted(swept.items(), key=lambda kv: -(kv[1][0].n if kv[1] else 0))
    out: list[Choice] = []
    for group, points in ordered:
        if not points or points[0].n < minimum:
            continue
        for target in targets:
            chosen = choose(points, target)
            if chosen is not None:
                out.append(
                    Choice(
                        group=group,
                        n=chosen.n,
                        target=chosen.target,
                        threshold=chosen.threshold,
                        accuracy=chosen.accuracy,
                        refusal_rate=chosen.refusal_rate,
                        mean_depth=chosen.mean_depth,
                        reached=chosen.reached,
                    )
                )
    return out


def document(choices: Sequence[Choice]) -> dict[str, dict[str, dict[str, float | bool]]]:
    """Keyed by target, because that is what the app will ask for.

    Each group carries what it actually delivers, not only the dial setting:

        {"0.95": {"Birds": {"threshold": 0.82, "accuracy": 0.954, "reached": true}, ...}}

    `accuracy` and `reached` are the difference between a promise and a guess. Four of the
    nine groups cannot reach 95% at any threshold — mammals top out at 89.7% — and a file
    that recorded only the number would let the app say "95% sure" over a model that is not.
    With this, the screen can say "as good as it gets: 90%" instead, which is true.
    """
    out: dict[str, dict[str, dict[str, float | bool]]] = {}
    for choice in choices:
        out.setdefault(f"{choice.target:.2f}", {})[choice.group] = {
            "threshold": choice.threshold,
            "accuracy": round(choice.accuracy, 4),
            "reached": choice.reached,
            "n": choice.n,
        }
    return {target: dict(sorted(groups.items())) for target, groups in sorted(out.items())}


def format_table(choices: Sequence[Choice]) -> str:
    header = (
        f"{'group':<12}{'n':>7}{'target':>8}{'threshold':>11}"
        f"{'accuracy':>10}{'refused':>9}{'depth':>7}"
    )
    lines = [header, "-" * len(header)]
    for choice in choices:
        flag = "" if choice.reached else "  *"
        lines.append(
            f"{choice.group:<12}{choice.n:>7,}{choice.target:>7.0%}{choice.threshold:>11.2f}"
            f"{choice.accuracy:>9.1%}{choice.refusal_rate:>9.1%}{choice.mean_depth:>7.2f}{flag}"
        )
    if any(not c.reached for c in choices):
        lines.append("")
        lines.append("  * the target is out of reach at any threshold; this is the best on offer")
    return "\n".join(lines)
