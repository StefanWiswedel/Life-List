"""Tests for iNaturalist open-data filtering — build plan §3, stage 2."""

from __future__ import annotations

import pandas as pd
import pytest

from lifelist_train import inat
from lifelist_train.inat import (
    SchemaError,
    assert_columns,
    coverage,
    filter_observations,
    filter_photos,
    join_photos_to_taxa,
    photo_url,
    sample_photos,
    select_taxa,
    taxa_at_thresholds,
)

DENMARK_BBOX = (54.5, 8.0, 57.8, 15.2)


def observations_df() -> pd.DataFrame:
    return pd.DataFrame(
        [
            # Copenhagen, research grade — keep
            ("obs-1", 100, "research", 55.68, 12.57),
            # Bornholm, research grade — keep (and the reason the box runs to 15.2)
            ("obs-2", 100, "research", 55.10, 14.90),
            # Copenhagen, needs_id — drop
            ("obs-3", 100, "needs_id", 55.68, 12.57),
            # Hamburg, research grade — outside Denmark, drop
            ("obs-4", 101, "research", 53.55, 9.99),
            # Aarhus, research grade — keep
            ("obs-5", 101, "research", 56.16, 10.20),
            # missing taxon — drop
            ("obs-6", None, "research", 55.68, 12.57),
        ],
        columns=["observation_uuid", "taxon_id", "quality_grade", "latitude", "longitude"],
    )


def photos_df() -> pd.DataFrame:
    return pd.DataFrame(
        [
            ("ph-1", 1, "obs-1", "jpg", "CC-BY"),
            ("ph-2", 2, "obs-1", "jpg", "CC0"),
            ("ph-3", 3, "obs-2", "jpeg", "CC-BY-NC"),
            ("ph-4", 4, "obs-3", "jpg", "CC0"),
            ("ph-5", 5, "obs-5", "png", "CC-BY-SA"),
            ("ph-6", 6, "obs-5", "jpg", "C"),  # all rights reserved — drop
            ("ph-7", 7, "obs-4", "jpg", "CC0"),
        ],
        columns=["photo_uuid", "photo_id", "observation_uuid", "extension", "license"],
    )


# -- schema guard ---------------------------------------------------------------


def test_missing_column_fails_loudly():
    """A silent mismatch would filter to zero rows and read as 'Denmark has no data'."""
    broken = observations_df().drop(columns=["quality_grade"])

    with pytest.raises(SchemaError, match="quality_grade"):
        assert_columns(broken, "observations")


def test_schema_error_names_what_it_actually_got():
    broken = observations_df().drop(columns=["latitude"])

    with pytest.raises(SchemaError, match="longitude"):
        assert_columns(broken, "observations")


# -- observation filtering ------------------------------------------------------


def test_only_research_grade_survives():
    out = filter_observations(observations_df(), bbox=DENMARK_BBOX)

    assert "obs-3" not in set(out["observation_uuid"])


def test_bounding_box_excludes_hamburg():
    out = filter_observations(observations_df(), bbox=DENMARK_BBOX)

    assert "obs-4" not in set(out["observation_uuid"])


def test_bounding_box_includes_bornholm():
    """A box stopping at 13°E quietly loses an island."""
    out = filter_observations(observations_df(), bbox=DENMARK_BBOX)

    assert "obs-2" in set(out["observation_uuid"])


def test_observations_without_a_taxon_are_dropped():
    out = filter_observations(observations_df(), bbox=DENMARK_BBOX)

    assert "obs-6" not in set(out["observation_uuid"])


def test_no_bbox_keeps_everything_research_grade():
    out = filter_observations(observations_df(), bbox=None)

    assert "obs-4" in set(out["observation_uuid"])


# -- photo filtering ------------------------------------------------------------


def test_closed_licence_photos_are_dropped():
    out = filter_photos(photos_df())

    assert 6 not in set(out["photo_id"])


def test_all_open_licence_variants_survive():
    out = filter_photos(photos_df())

    assert set(out["photo_id"]) == {1, 2, 3, 4, 5, 7}


def test_licence_matching_is_case_and_separator_insensitive():
    photos = pd.DataFrame(
        [("ph-1", 1, "obs-1", "jpg", "cc_by_nc")],
        columns=["photo_uuid", "photo_id", "observation_uuid", "extension", "license"],
    )

    assert len(filter_photos(photos)) == 1


# -- joining and coverage -------------------------------------------------------


def test_join_attaches_taxa_and_drops_orphans():
    obs = filter_observations(observations_df(), bbox=DENMARK_BBOX)
    photos = filter_photos(photos_df())
    joined = join_photos_to_taxa(obs, photos)

    assert set(joined["photo_id"]) == {1, 2, 3, 5}
    assert set(joined["taxon_id"]) == {100, 101}


def test_coverage_counts_observations_and_photos_separately():
    obs = filter_observations(observations_df(), bbox=DENMARK_BBOX)
    joined = join_photos_to_taxa(obs, filter_photos(photos_df()))
    by_taxon = {c.taxon_id: c for c in coverage(joined)}

    assert by_taxon[100].observations == 2  # obs-1, obs-2
    assert by_taxon[100].photos == 3  # ph-1, ph-2, ph-3
    assert by_taxon[101].observations == 1
    assert by_taxon[101].photos == 1


def test_coverage_is_sorted_commonest_first():
    obs = filter_observations(observations_df(), bbox=DENMARK_BBOX)
    joined = join_photos_to_taxa(obs, filter_photos(photos_df()))

    assert [c.taxon_id for c in coverage(joined)] == [100, 101]


# -- the stop-and-ask report ----------------------------------------------------


def test_thresholds_count_observations_not_photos():
    """Ten photos of one beetle is one piece of evidence about that species."""
    joined = pd.DataFrame(
        {
            "taxon_id": [1] * 10,
            "observation_uuid": ["obs-1"] * 10,
            "photo_id": range(10),
        }
    )

    assert taxa_at_thresholds(coverage(joined), thresholds=(2,)) == {2: 0}


def test_threshold_counts_are_monotone():
    joined = pd.DataFrame(
        {
            "taxon_id": [t for t in range(5) for _ in range(t * 30)],
            "observation_uuid": [f"o{t}-{i}" for t in range(5) for i in range(t * 30)],
            "photo_id": range(sum(t * 30 for t in range(5))),
        }
    )
    counts = taxa_at_thresholds(coverage(joined), thresholds=(50, 80, 120))

    assert counts[50] >= counts[80] >= counts[120]


# -- selection ------------------------------------------------------------------


def test_selection_drops_taxa_below_the_minimum():
    coverages = coverage(
        pd.DataFrame(
            {
                "taxon_id": [1] * 100 + [2] * 10,
                "observation_uuid": [f"a{i}" for i in range(100)] + [f"b{i}" for i in range(10)],
                "photo_id": range(110),
            }
        )
    )

    assert set(select_taxa(coverages, min_observations=80)) == {1}


def test_photo_cap_limits_class_imbalance():
    coverages = coverage(
        pd.DataFrame(
            {
                "taxon_id": [1] * 900,
                "observation_uuid": [f"a{i}" for i in range(900)],
                "photo_id": range(900),
            }
        )
    )

    assert select_taxa(coverages, min_observations=80, max_photos_per_taxon=500) == {1: 500}


# -- sampling -------------------------------------------------------------------


def test_sampling_spreads_across_observations_before_repeating_one():
    """A taxon with 500 photos from 3 observations is three individuals photographed
    repeatedly; a flat photo sample would fill the class with near-duplicates."""
    joined = pd.DataFrame(
        {
            "taxon_id": [1] * 30,
            # observation A has 20 photos, B has 5, C has 5
            "observation_uuid": ["A"] * 20 + ["B"] * 5 + ["C"] * 5,
            "photo_id": range(30),
        }
    )
    sampled = sample_photos(joined, {1: 6}, seed=0)

    assert len(sampled) == 6
    assert set(sampled["observation_uuid"]) == {"A", "B", "C"}
    assert sampled["observation_uuid"].value_counts().max() == 2


def test_sampling_is_deterministic():
    joined = pd.DataFrame(
        {
            "taxon_id": [1] * 20,
            "observation_uuid": [f"o{i // 2}" for i in range(20)],
            "photo_id": range(20),
        }
    )

    first = sample_photos(joined, {1: 7}, seed=3)["photo_id"].tolist()
    second = sample_photos(joined, {1: 7}, seed=3)["photo_id"].tolist()

    assert first == second


def test_sampling_a_missing_taxon_is_harmless():
    joined = pd.DataFrame(
        {"taxon_id": [1], "observation_uuid": ["o1"], "photo_id": [1]}
    )

    assert len(sample_photos(joined, {999: 10})) == 0


# -- urls -----------------------------------------------------------------------


def test_photo_url_uses_the_medium_size():
    url = photo_url(12345, "jpg")

    assert url == "https://inaturalist-open-data.s3.amazonaws.com/photos/12345/medium.jpg"


def test_photo_url_tolerates_a_leading_dot_extension():
    assert photo_url(1, ".png").endswith("/medium.png")


def test_photo_url_defaults_a_missing_extension():
    assert photo_url(1, "").endswith("/medium.jpg")


# -- one photo, two taxa --------------------------------------------------------
#
# The photos table is not one row per photo. Found by running stage 3 over the real
# manifest: 565 Danish photo_ids carry more than one taxon.


def _obs(rows):
    return pd.DataFrame(rows, columns=["observation_uuid", "taxon_id"])


def _photos(rows):
    return pd.DataFrame(
        rows, columns=["photo_uuid", "photo_id", "observation_uuid", "extension", "license"]
    )


def test_a_photo_under_two_taxa_is_dropped_not_guessed():
    observations = _obs([("obs-a", 11), ("obs-b", 22)])
    photos = _photos(
        [
            ("pu-1", 1, "obs-a", "jpg", "CC0"),
            ("pu-1", 1, "obs-b", "jpg", "CC0"),
            ("pu-2", 2, "obs-a", "jpg", "CC0"),
        ]
    )

    joined = inat.join_photos_to_taxa(observations, photos)

    assert joined["photo_id"].tolist() == [2]


def test_a_photo_repeated_within_one_taxon_is_collapsed_not_dropped():
    observations = _obs([("obs-a", 11), ("obs-b", 11)])
    photos = _photos(
        [
            ("pu-1", 1, "obs-a", "jpg", "CC0"),
            ("pu-1", 1, "obs-b", "jpg", "CC0"),
        ]
    )

    joined = inat.join_photos_to_taxa(observations, photos)

    assert joined["photo_id"].tolist() == [1]
    assert joined["taxon_id"].tolist() == [11]


def test_photo_ids_are_unique_after_the_join():
    observations = _obs([(f"obs-{i}", i % 3) for i in range(12)])
    photos = _photos(
        [(f"pu-{i % 5}", i % 5, f"obs-{i}", "jpg", "CC0") for i in range(12)]
    )

    joined = inat.join_photos_to_taxa(observations, photos)

    assert joined["photo_id"].is_unique
