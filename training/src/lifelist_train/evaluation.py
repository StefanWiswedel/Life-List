"""Stage 5 — accuracy broken down by the thing being identified.

`head.py` reports one rollup accuracy, one leaf top-1, one ECE. Those are the right headline
numbers and they have been quietly hiding the answer to the question that keeps coming back from
real use: *how good is it on moths?*

A model at 94.7% rollup accuracy can be 97% on birds and 70% on micro-moths, and only one of
those describes the evening someone actually had. Two identifications in a row came back with a
confident species that was visibly the wrong moth, and nothing in the repo could say whether
that was bad luck or a group the model is simply weak on.

This also decides something concrete: **whether the confidence threshold should differ by
group.** If moths need 90% to be as trustworthy as birds are at 70%, that is a fact about the
model worth acting on rather than a preference to leave with the user.

Pure functions over already-computed logits. No files, no model, no network — the CLI does that.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np

from .groups import DEFAULT_GROUPS, group_of
from .head import Evaluation, evaluate
from .rollup import DEFAULT_THRESHOLD, Taxonomy


@dataclass(frozen=True, slots=True)
class GroupScore:
    """One row of the table."""

    label: str
    #: Test examples in this group. Small n makes the rest of the row noise; report it.
    n: int
    #: Distinct taxa the group covers in the test set.
    taxa: int
    evaluation: Evaluation

    @property
    def reliable(self) -> bool:
        """Below this, the numbers are anecdotes with decimal points.

        Chosen so a single misidentification cannot move accuracy by more than a point.
        """
        return self.n >= 100


def labels_for(
    taxonomy: Taxonomy,
    true_leaf_indices: Sequence[int],
    groups: Sequence[tuple[str, int]] = DEFAULT_GROUPS,
) -> list[str]:
    """The group each test example belongs to, by the true taxon rather than the predicted one.

    By the *true* taxon on purpose. Grouping by the prediction would let a model that calls
    every moth a beetle report excellent accuracy on moths, because it would have no moths left
    to be wrong about.
    """
    return [
        group_of(taxonomy.lineage(taxonomy.leaf_id(int(index))), groups)
        for index in true_leaf_indices
    ]


def by_group(
    taxonomy: Taxonomy,
    logits: np.ndarray,
    true_leaf_indices: np.ndarray,
    temperature: float = 1.0,
    threshold: float = DEFAULT_THRESHOLD,
    groups: Sequence[tuple[str, int]] = DEFAULT_GROUPS,
    minimum: int = 1,
) -> list[GroupScore]:
    """Score each group separately, biggest first.

    The temperature is the one fitted on the whole validation set, deliberately. Fitting a
    temperature per group would make every group look well calibrated and destroy the finding —
    the point is to discover that one scalar does not serve every group equally.
    """
    labels = np.array(labels_for(taxonomy, true_leaf_indices, groups))
    out: list[GroupScore] = []

    for label in dict.fromkeys(labels.tolist()):
        mask = labels == label
        if int(mask.sum()) < minimum:
            continue
        subset = true_leaf_indices[mask]
        out.append(
            GroupScore(
                label=label,
                n=int(mask.sum()),
                taxa=int(np.unique(subset).size),
                evaluation=evaluate(
                    taxonomy,
                    logits[mask],
                    subset,
                    temperature=temperature,
                    threshold=threshold,
                ),
            )
        )
    return sorted(out, key=lambda row: row.n, reverse=True)


def threshold_sweep(
    taxonomy: Taxonomy,
    logits: np.ndarray,
    true_leaf_indices: np.ndarray,
    temperature: float,
    thresholds: Sequence[float],
    groups: Sequence[tuple[str, int]] = DEFAULT_GROUPS,
) -> dict[float, list[GroupScore]]:
    """The same table at several confidence thresholds.

    What it is for: finding the threshold at which each group becomes trustworthy. If birds
    reach 95% rollup accuracy at 0.70 and moths need 0.90 to get there, the app is currently
    offering one dial for two very different instruments.
    """
    return {
        threshold: by_group(
            taxonomy, logits, true_leaf_indices, temperature, threshold, groups
        )
        for threshold in thresholds
    }


def format_table(rows: Sequence[GroupScore]) -> str:
    """The table, as text, because that is what goes into RESULTS.md."""
    header = (
        f"{'group':<22}{'n':>7}{'taxa':>7}{'rollup':>9}"
        f"{'top-1':>8}{'ECE':>7}{'depth':>7}{'refused':>9}"
    )
    lines = [header, "-" * len(header)]
    for row in rows:
        e = row.evaluation
        flag = "" if row.reliable else "  *"
        lines.append(
            f"{row.label:<22}{row.n:>7}{row.taxa:>7}{e.rollup_accuracy * 100:>8.1f}%"
            f"{e.leaf_top1 * 100:>7.1f}%{e.expected_calibration_error:>7.3f}"
            f"{e.mean_returned_depth:>7.2f}{e.refusal_rate * 100:>8.1f}%{flag}"
        )
    if any(not row.reliable for row in rows):
        lines.append("")
        lines.append("  * fewer than 100 test examples — read as an anecdote, not a measurement")
    return "\n".join(lines)
