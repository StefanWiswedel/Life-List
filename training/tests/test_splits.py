"""Tests for observation-level splitting — build plan §3, stage 3.

The brief calls photo-level splitting "the single most likely source of a falsely
optimistic accuracy number". These tests are the assertion it asks for.
"""

from __future__ import annotations

import pytest

from lifelist_train.splits import (
    Photo,
    assert_no_observation_leakage,
    assign_splits,
    report,
    split_photos,
    taxon_count_at_thresholds,
)


def make_photos(n_obs: int = 300, photos_per_obs: int = 4, n_taxa: int = 10) -> list[Photo]:
    return [
        Photo(
            photo_id=obs * 100 + k,
            observation_id=obs,
            taxon_id=obs % n_taxa,
        )
        for obs in range(n_obs)
        for k in range(photos_per_obs)
    ]


# -- the property that matters --------------------------------------------------


def test_no_observation_straddles_two_splits():
    splits = split_photos(make_photos())
    assert_no_observation_leakage(splits)


def test_leakage_assertion_actually_catches_leakage():
    """A guard that cannot fail is not a guard."""
    leaky = {
        "train": [Photo(1, 100, 5)],
        "val": [Photo(2, 100, 5)],  # same observation, different split
        "test": [],
    }
    with pytest.raises(AssertionError, match="appears in both"):
        assert_no_observation_leakage(leaky)


def test_all_photos_of_an_observation_share_a_split():
    photos = make_photos(photos_per_obs=5)
    splits = split_photos(photos)

    location = {}
    for name, split_photos_list in splits.items():
        for p in split_photos_list:
            location.setdefault(p.observation_id, name)
            assert location[p.observation_id] == name


def test_every_photo_lands_somewhere_exactly_once():
    photos = make_photos()
    splits = split_photos(photos)

    landed = [p.photo_id for group in splits.values() for p in group]
    assert sorted(landed) == sorted(p.photo_id for p in photos)
    assert len(landed) == len(set(landed))


# -- determinism ----------------------------------------------------------------


def test_split_is_reproducible():
    photos = make_photos()
    assert assign_splits(photos, seed=42) == assign_splits(photos, seed=42)


def test_different_seeds_give_different_splits():
    photos = make_photos()
    assert assign_splits(photos, seed=1) != assign_splits(photos, seed=2)


def test_adding_observations_does_not_move_existing_ones():
    """Re-running stage 3 after fetching more data must not reshuffle the split.

    Otherwise every incremental data pull silently invalidates every previous
    accuracy number.
    """
    first = make_photos(n_obs=200)
    second = make_photos(n_obs=400)

    a = assign_splits(first, seed=7)
    b = assign_splits(second, seed=7)

    assert all(b[obs] == split for obs, split in a.items())


def test_assignment_is_independent_of_input_order():
    photos = make_photos(n_obs=100)
    assert assign_splits(photos, seed=3) == assign_splits(list(reversed(photos)), seed=3)


# -- proportions ----------------------------------------------------------------


def test_split_proportions_are_approximately_right():
    photos = make_photos(n_obs=4000, photos_per_obs=1)
    splits = split_photos(photos, fractions=(0.80, 0.10, 0.10))
    total = sum(len(v) for v in splits.values())

    assert len(splits["train"]) / total == pytest.approx(0.80, abs=0.02)
    assert len(splits["val"]) / total == pytest.approx(0.10, abs=0.02)
    assert len(splits["test"]) / total == pytest.approx(0.10, abs=0.02)


def test_rejects_fractions_that_do_not_sum_to_one():
    with pytest.raises(ValueError, match="must sum to 1.0"):
        split_photos(make_photos(), fractions=(0.8, 0.1, 0.2))


def test_rejects_negative_fractions():
    with pytest.raises(ValueError, match="non-negative"):
        split_photos(make_photos(), fractions=(1.2, -0.1, -0.1))


# -- reporting ------------------------------------------------------------------


def test_report_counts_observations_not_just_photos():
    photos = make_photos(n_obs=100, photos_per_obs=4)
    r = report(photos_to_splits(photos))

    assert sum(r.counts.values()) == 400
    assert sum(r.observation_counts.values()) == 100


def test_report_flags_a_taxon_absent_from_train():
    # one taxon appearing in a single observation will land in exactly one split
    photos = make_photos(n_obs=50, n_taxa=5) + [Photo(99999, 99999, 777)]
    r = report(photos_to_splits(photos))

    assert (777 in r.taxa_missing_from_train) or (777 in r.taxa_missing_from_val)
    assert not r.is_usable


def test_report_flags_thin_classes():
    photos = make_photos(n_obs=40, photos_per_obs=1, n_taxa=20)
    r = report(photos_to_splits(photos), min_train_photos=10)

    assert r.taxa_below_minimum, "20 taxa across 40 observations must be flagged as thin"


def photos_to_splits(photos: list[Photo]) -> dict[str, list[Photo]]:
    return split_photos(photos)


# -- the stop-and-ask report ----------------------------------------------------


def test_taxon_count_at_thresholds_is_monotone():
    """A stricter minimum can never admit more taxa."""
    photos = make_photos(n_obs=2000, photos_per_obs=3, n_taxa=40)
    counts = taxon_count_at_thresholds(photos, thresholds=(10, 20, 50, 100))
    values = [counts[t] for t in (10, 20, 50, 100)]

    assert values == sorted(values, reverse=True)


def test_taxon_count_thresholds_on_observations_not_photos():
    """Ten photos of one beetle is one piece of evidence, not ten."""
    photos = [Photo(photo_id=k, observation_id=1, taxon_id=1) for k in range(100)]
    counts = taxon_count_at_thresholds(photos, thresholds=(2,))

    assert counts[2] == 0
