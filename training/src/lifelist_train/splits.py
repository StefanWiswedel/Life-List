"""Train/val/test splitting — build plan §3, stage 3.

Splitting is by **observation**, never by photo. Multiple photos of the same individual
straddling train and validation is the single most likely source of a falsely optimistic
accuracy number, and it is invisible in the metrics: everything just looks good.

Every function here is deterministic given a seed, and the leakage property is asserted
in tests rather than assumed.
"""

from __future__ import annotations

import hashlib
from collections import Counter, defaultdict
from dataclasses import dataclass

DEFAULT_FRACTIONS = (0.80, 0.10, 0.10)
SPLIT_NAMES = ("train", "val", "test")


@dataclass(frozen=True, slots=True)
class Photo:
    photo_id: int
    observation_id: int
    taxon_id: int


@dataclass(frozen=True, slots=True)
class SplitReport:
    counts: dict[str, int]
    observation_counts: dict[str, int]
    taxa_missing_from_train: tuple[int, ...]
    taxa_missing_from_val: tuple[int, ...]
    taxa_below_minimum: tuple[tuple[int, int], ...]

    @property
    def is_usable(self) -> bool:
        """Any taxon absent from train is unlearnable; absent from val is unmeasurable."""
        return not self.taxa_missing_from_train and not self.taxa_missing_from_val


def _stable_fraction(observation_id: int, seed: int) -> float:
    """Deterministic uniform-ish value in [0, 1) for an observation.

    Hashing rather than shuffling so a split is reproducible across machines, runs and
    Python versions, and so re-running stage 3 after adding new observations leaves
    existing assignments untouched. `hash()` is salted per-process and would not do.
    """
    digest = hashlib.sha256(f"{seed}:{observation_id}".encode()).digest()
    return int.from_bytes(digest[:8], "big") / (1 << 64)


def assign_splits(
    photos: list[Photo],
    fractions: tuple[float, float, float] = DEFAULT_FRACTIONS,
    seed: int = 0,
) -> dict[int, str]:
    """Assign each **observation** to a split. Photos inherit their observation's split."""
    if len(fractions) != 3:
        raise ValueError("fractions must be (train, val, test)")
    if abs(sum(fractions) - 1.0) > 1e-9:
        raise ValueError(f"fractions must sum to 1.0, got {sum(fractions)}")
    if any(f < 0 for f in fractions):
        raise ValueError("fractions must be non-negative")

    train_end = fractions[0]
    val_end = fractions[0] + fractions[1]

    out: dict[int, str] = {}
    for obs_id in {p.observation_id for p in photos}:
        u = _stable_fraction(obs_id, seed)
        out[obs_id] = "train" if u < train_end else "val" if u < val_end else "test"
    return out


def split_photos(
    photos: list[Photo],
    fractions: tuple[float, float, float] = DEFAULT_FRACTIONS,
    seed: int = 0,
) -> dict[str, list[Photo]]:
    assignment = assign_splits(photos, fractions, seed)
    out: dict[str, list[Photo]] = {name: [] for name in SPLIT_NAMES}
    for photo in photos:
        out[assignment[photo.observation_id]].append(photo)
    return out


def assert_no_observation_leakage(splits: dict[str, list[Photo]]) -> None:
    """Raise if any observation appears in more than one split.

    Called at the end of stage 3, not only in tests. The failure this guards against is
    silent and flattering, so it deserves a runtime assertion in the pipeline itself.
    """
    owner: dict[int, str] = {}
    for split_name, photos in splits.items():
        for photo in photos:
            previous = owner.setdefault(photo.observation_id, split_name)
            if previous != split_name:
                raise AssertionError(
                    f"observation {photo.observation_id} appears in both "
                    f"{previous!r} and {split_name!r} — photos of one individual have "
                    "straddled the split, which inflates accuracy invisibly"
                )


def report(splits: dict[str, list[Photo]], min_train_photos: int = 20) -> SplitReport:
    """Summarise a split, surfacing the taxa that will quietly break training."""
    all_taxa = {p.taxon_id for photos in splits.values() for p in photos}

    per_split_taxa = {
        name: {p.taxon_id for p in photos} for name, photos in splits.items()
    }
    train_counts = Counter(p.taxon_id for p in splits["train"])

    return SplitReport(
        counts={name: len(photos) for name, photos in splits.items()},
        observation_counts={
            name: len({p.observation_id for p in photos}) for name, photos in splits.items()
        },
        taxa_missing_from_train=tuple(sorted(all_taxa - per_split_taxa["train"])),
        taxa_missing_from_val=tuple(sorted(all_taxa - per_split_taxa["val"])),
        taxa_below_minimum=tuple(
            sorted(
                (taxon, train_counts.get(taxon, 0))
                for taxon in all_taxa
                if train_counts.get(taxon, 0) < min_train_photos
            )
        ),
    )


def taxon_count_at_thresholds(
    photos: list[Photo],
    thresholds: tuple[int, ...] = (50, 80, 120, 200),
) -> dict[int, int]:
    """How many taxa survive each minimum-image threshold (build plan, stage 2).

    This is the report that stops the pipeline for a human decision: the taxon count
    determines everything downstream, and the tradeoff between coverage and per-class
    data should be visible before it is committed to.

    Counts **observations**, not photos — ten photos of one beetle is one piece of
    evidence about that species, and thresholding on photos overstates coverage.
    """
    per_taxon: defaultdict[int, set[int]] = defaultdict(set)
    for photo in photos:
        per_taxon[photo.taxon_id].add(photo.observation_id)
    return {
        threshold: sum(1 for obs in per_taxon.values() if len(obs) >= threshold)
        for threshold in thresholds
    }
