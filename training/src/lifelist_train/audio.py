"""Audio identification — reference implementation of shared/taxonomy-spec.md §4A.

BirdNET emits independent per-class sigmoid confidences, not a softmax. Feeding those
into a mass-summing rollup would produce authoritative-looking nonsense, so this module
separates detection (multi-label, BirdNET's native semantics) from identification
(a conditional distribution the §4 rollup can consume unchanged).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .rollup import RollupResult, rollup
from .taxonomy import RANK_ORDER, Taxonomy

DEFAULT_DETECTION_THRESHOLD = 0.25
DEFAULT_CONFUSION_MARGIN = 0.5
DEFAULT_GEO_WEIGHT = 1.0

# Confusion sets are only coherent within a family; a frog and a warbler singing at the
# same time are two detections, not two candidates for one identification.
MAX_LCA_RANK = RANK_ORDER["family"]


@dataclass(frozen=True, slots=True)
class Detection:
    """One class over threshold in one window, before identification."""

    taxon_id: int
    score: float
    window_start_s: float


@dataclass(frozen=True, slots=True)
class AudioIdentification:
    detection: Detection
    result: RollupResult
    confusion_set: tuple[int, ...]
    geo_applied: bool
    raw_scores: dict[int, float]
    """Pre-prior scores, kept so a suppressed vagrant stays recoverable (spec §4A.4)."""


def detect(
    scores: dict[int, float],
    window_start_s: float = 0.0,
    detection_threshold: float = DEFAULT_DETECTION_THRESHOLD,
) -> list[Detection]:
    """Spec §4A.1 — multi-label. Several species really can be singing at once."""
    if not 0.0 < detection_threshold < 1.0:
        raise ValueError(f"detection_threshold must be in (0, 1), got {detection_threshold}")
    out = [
        Detection(taxon_id=tid, score=float(s), window_start_s=window_start_s)
        for tid, s in scores.items()
        if s >= detection_threshold
    ]
    # descending score, ties by lower taxon_id — deterministic, as everywhere else
    out.sort(key=lambda d: (-d.score, d.taxon_id))
    return out


def apply_geo_prior(
    scores: dict[int, float],
    geo: dict[int, float],
    weight: float = DEFAULT_GEO_WEIGHT,
) -> dict[int, float]:
    """Spec §4A.4 — a prior, never a mask.

    A hard range filter would make a genuine vagrant unloggable, which is exactly the
    record a naturalist most wants. Weight 0.0 disables.
    """
    if weight < 0:
        raise ValueError(f"geo_weight must be non-negative, got {weight}")
    if weight == 0.0:
        return dict(scores)
    return {
        tid: float(s * (max(geo.get(tid, 0.0), 1e-6) ** weight)) for tid, s in scores.items()
    }


def lca_rank_depth(tax: Taxonomy, a: int, b: int) -> int:
    """Rank depth of the lowest common ancestor of two taxa."""
    lineage_a = tax.lineage(a)
    seen = set(lineage_a)
    for node in reversed(tax.lineage(b)):
        if node in seen:
            return RANK_ORDER[tax.node(node).rank]
    return RANK_ORDER["root"]


def confusion_set(
    tax: Taxonomy,
    scores: dict[int, float],
    detection: Detection,
    margin: float = DEFAULT_CONFUSION_MARGIN,
) -> tuple[int, ...]:
    """Spec §4A.2 — taxonomically coherent competitors scoring within ``margin``."""
    if not 0.0 < margin <= 1.0:
        raise ValueError(f"margin must be in (0, 1], got {margin}")
    floor = detection.score * margin
    members = {detection.taxon_id}
    for tid, s in scores.items():
        if tid == detection.taxon_id:
            continue
        if s >= floor and lca_rank_depth(tax, detection.taxon_id, tid) >= MAX_LCA_RANK:
            members.add(tid)
    return tuple(sorted(members))


def identify(
    tax: Taxonomy,
    scores: dict[int, float],
    detection: Detection,
    threshold: float,
    margin: float = DEFAULT_CONFUSION_MARGIN,
    geo: dict[int, float] | None = None,
    geo_weight: float = DEFAULT_GEO_WEIGHT,
) -> AudioIdentification:
    """Resolve one detection to its deepest defensible rank (spec §4A.2–§4A.3).

    Two Phylloscopus candidates at 0.45 and 0.40 resolve to the genus rather than to a
    coin-flip binomial — the same property the vision path has, from the same rollup.
    """
    raw = dict(scores)

    # Order matters. The confusion set is built from RAW scores, and the prior is applied
    # only afterwards, within the set. Applying it first lets a strong prior drop a
    # candidate below the margin and out of the set entirely — which is a silent mask,
    # the exact behaviour spec §4A.4 forbids. A vagrant must stay visible and outvoted,
    # not erased.
    members = confusion_set(tax, raw, detection, margin)
    effective = apply_geo_prior(raw, geo, geo_weight) if geo else raw

    total = sum(effective[m] for m in members)
    if total <= 0:
        raise ValueError("confusion set has zero total score")

    # Project onto the full leaf vector: everything outside the confusion set is
    # conditioned away, which is the point — we are asking "given one of these, which?"
    p = np.zeros(tax.n_taxa, dtype=np.float64)
    for m in members:
        node = tax.node(m)
        if node.leaf_index is None:
            raise ValueError(f"taxon {m} is not a leaf and cannot carry a detection score")
        p[node.leaf_index] = effective[m] / total

    return AudioIdentification(
        detection=detection,
        result=rollup(tax, p, threshold=threshold),
        confusion_set=members,
        geo_applied=bool(geo) and geo_weight > 0.0,
        raw_scores={m: raw[m] for m in members},
    )


def identify_window(
    tax: Taxonomy,
    scores: dict[int, float],
    threshold: float,
    window_start_s: float = 0.0,
    detection_threshold: float = DEFAULT_DETECTION_THRESHOLD,
    margin: float = DEFAULT_CONFUSION_MARGIN,
    geo: dict[int, float] | None = None,
    geo_weight: float = DEFAULT_GEO_WEIGHT,
) -> list[AudioIdentification]:
    """Full pipeline for one 3-second window: detect, then identify each detection."""
    return [
        identify(tax, scores, d, threshold, margin, geo, geo_weight)
        for d in detect(scores, window_start_s, detection_threshold)
    ]
