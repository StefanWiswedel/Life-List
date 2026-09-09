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

#: A range likelihood of zero must lower a species' odds, never make it unloggable.
GEO_FLOOR = 1e-6


def f32(x: float) -> float:
    """Round to float32 and hand back a Python float.

    Every score in this module is a float32 on the phone — BirdNET emits fp16 or fp32 and
    Kotlin holds `Float`. Carrying float64 through here instead would make the golden fixture
    disagree with the app in the seventh decimal for no reason anybody could act on, and would
    make a real disagreement impossible to spot among the noise.
    """
    return float(np.float32(x))

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
    absent: float
    """Mass held by "none of these" (spec §4A.3). 0.74 for a lone detection scoring 0.26."""

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
        Detection(taxon_id=tid, score=f32(s), window_start_s=window_start_s)
        for tid, s in scores.items()
        if f32(s) >= f32(detection_threshold)
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

    Multiplied in float64 and stored back in float32, because that is what the phone does:
    `Audio.applyGeoPrior` widens two floats, raises to the power, and narrows the result.
    Doing it any other way here would put a difference into the golden fixture that is not a
    difference in the algorithm.
    """
    if weight < 0:
        raise ValueError(f"geo_weight must be non-negative, got {weight}")
    if weight == 0.0:
        return {tid: f32(s) for tid, s in scores.items()}
    return {
        tid: f32(np.float64(f32(s)) * max(np.float64(f32(geo.get(tid, 0.0))), GEO_FLOOR) ** weight)
        for tid, s in scores.items()
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
    # float32 product, as Kotlin's `detection.score * margin` is — the margin decides set
    # membership, so a difference here is a different answer, not a rounding.
    floor = f32(np.float32(detection.score) * np.float32(margin))
    members = {detection.taxon_id}
    for tid, s in scores.items():
        if tid == detection.taxon_id:
            continue
        if f32(s) >= floor and lca_rank_depth(tax, detection.taxon_id, tid) >= MAX_LCA_RANK:
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

    # "None of these" — spec §4A.3.
    #
    # Renormalising across the confusion set alone answers "given that this sound is one of
    # these, which is it?" and throws away the question the user actually cares about first:
    # whether anything is there at all. A lone detection then divides by itself and comes back
    # at 100%, so BirdNET at 0.26 was presented exactly like BirdNET at 0.99 and no threshold
    # could refuse either (VERIFICATION §60).
    #
    # BirdNET's scores are independent per-class probabilities, so P(none of them present) is
    # the product of their complements. Giving that outcome its own mass — outside the tree,
    # where no taxon can claim it — restores the missing question. A single member collapses to
    # `s / (s + (1 - s)) = s`, which is the right answer stated the obvious way: with nothing to
    # confuse it with, the app is exactly as sure as BirdNET was.
    # The subtraction is float32, as Kotlin's `1f - score` is, then widened for the product —
    # float64 there because Kotlin multiplies in Double. Every narrowing in this module sits
    # where the phone has one.
    absent = np.float64(1.0)
    for m in members:
        complement = np.float32(1.0) - np.float32(f32(effective[m]))
        absent *= np.float64(min(max(float(complement), 0.0), 1.0))

    # float64 accumulation over the ascending member list, exactly as Kotlin's `sumOf` does.
    total = sum(np.float64(f32(effective[m])) for m in members) + absent
    if total <= 0:
        raise ValueError("confusion set has zero total score")

    # Project onto the full leaf vector: everything outside the confusion set is
    # conditioned away, which is the point — we are asking "given one of these, which?"
    p = np.zeros(tax.n_taxa, dtype=np.float32)
    for m in members:
        node = tax.node(m)
        if node.leaf_index is None:
            raise ValueError(f"taxon {m} is not a leaf and cannot carry a detection score")
        p[node.leaf_index] = f32(np.float64(f32(effective[m])) / total)

    reserved = f32(absent / total)
    return AudioIdentification(
        detection=detection,
        result=rollup(tax, p, threshold=threshold, reserved=reserved),
        confusion_set=members,
        geo_applied=bool(geo) and geo_weight > 0.0,
        absent=reserved,
        raw_scores={m: f32(raw[m]) for m in members},
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
