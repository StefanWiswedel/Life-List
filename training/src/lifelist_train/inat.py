"""iNaturalist open-data filtering — build plan §3, stage 2.

The bucket publishes one archive, `inaturalist-open-data-latest.tar.gz`, containing six
tab-separated files. The original brief described six separate downloads; it is one.

Column names below are asserted at runtime rather than assumed. The public README does
not document them, and a wrong guess would not raise — it would filter to zero rows, or
worse, filter to the wrong rows and look plausible.
"""

from __future__ import annotations

from dataclasses import dataclass

import pandas as pd

# Licences we may derive embeddings from. We redistribute embeddings, never images, but
# provenance is logged per photo regardless (build plan §3 stage 2).
OPEN_LICENCES = frozenset({"CC0", "CC-BY", "CC-BY-SA", "CC-BY-NC", "CC-BY-NC-SA"})

RESEARCH_GRADE = "research"

REQUIRED_COLUMNS = {
    "observations": {"observation_uuid", "taxon_id", "quality_grade", "latitude", "longitude"},
    "photos": {"photo_uuid", "photo_id", "observation_uuid", "extension", "license"},
}

PHOTO_SIZE = "medium"  # 500 px — we resize to 224 anyway; `original` is ~10x the bytes


class SchemaError(ValueError):
    """Raised when a table lacks a column the pipeline depends on."""


@dataclass(frozen=True, slots=True)
class TaxonCoverage:
    taxon_id: int
    observations: int
    photos: int


def assert_columns(df: pd.DataFrame, table: str) -> None:
    """Fail loudly if the released schema is not what we expect.

    A silent mismatch filters to zero rows — which looks like "Denmark has no
    research-grade observations" rather than like a bug.
    """
    required = REQUIRED_COLUMNS.get(table, set())
    missing = required - set(df.columns)
    if missing:
        raise SchemaError(
            f"{table} table is missing expected columns {sorted(missing)}; "
            f"got {sorted(df.columns)}. The iNaturalist release schema may have changed — "
            "check the archive rather than adjusting the filter to fit."
        )


def filter_observations(
    observations: pd.DataFrame,
    bbox: tuple[float, float, float, float] | None = None,
    quality_grade: str = RESEARCH_GRADE,
) -> pd.DataFrame:
    """Restrict to research-grade observations inside a bounding box.

    ``bbox`` is (min_lat, min_lon, max_lat, max_lon). Denmark's mainland-plus-islands box
    is roughly (54.5, 8.0, 57.8, 15.2); Bornholm sits at the eastern edge, so a box that
    stops at 13 quietly loses an island.
    """
    assert_columns(observations, "observations")

    out = observations[observations["quality_grade"] == quality_grade]
    out = out.dropna(subset=["taxon_id"])

    if bbox is not None:
        min_lat, min_lon, max_lat, max_lon = bbox
        out = out[
            out["latitude"].between(min_lat, max_lat)
            & out["longitude"].between(min_lon, max_lon)
        ]
    return out.copy()


def filter_photos(photos: pd.DataFrame, licences: frozenset[str] = OPEN_LICENCES) -> pd.DataFrame:
    """Restrict to openly licensed photos, keeping the licence for provenance."""
    assert_columns(photos, "photos")
    normalised = photos["license"].astype(str).str.upper().str.replace("_", "-", regex=False)
    return photos[normalised.isin({lic.upper() for lic in licences})].copy()


def join_photos_to_taxa(observations: pd.DataFrame, photos: pd.DataFrame) -> pd.DataFrame:
    """Attach each surviving photo to its observation's taxon."""
    return photos.merge(
        observations[["observation_uuid", "taxon_id"]],
        on="observation_uuid",
        how="inner",
    )


def coverage(joined: pd.DataFrame) -> list[TaxonCoverage]:
    """Per-taxon observation and photo counts, commonest first."""
    grouped = joined.groupby("taxon_id").agg(
        observations=("observation_uuid", "nunique"),
        photos=("photo_id", "count"),
    )
    return sorted(
        (
            TaxonCoverage(int(taxon_id), int(row.observations), int(row.photos))
            for taxon_id, row in grouped.iterrows()
        ),
        key=lambda c: (-c.observations, c.taxon_id),
    )


def taxa_at_thresholds(
    coverages: list[TaxonCoverage],
    thresholds: tuple[int, ...] = (50, 80, 120, 200),
) -> dict[int, int]:
    """How many taxa survive each minimum — the stop-and-ask report (build plan §8 step 4).

    Thresholds on **observations**, not photos. Ten photos of one beetle is one piece of
    evidence about that species; counting photos would overstate coverage in exactly the
    number a human is being asked to make a decision about.
    """
    return {
        threshold: sum(1 for c in coverages if c.observations >= threshold)
        for threshold in thresholds
    }


def select_taxa(
    coverages: list[TaxonCoverage],
    min_observations: int = 80,
    max_photos_per_taxon: int = 500,
) -> dict[int, int]:
    """Choose the taxa to train on and how many photos to take from each.

    Returns taxon_id → photo cap. The cap limits class imbalance without discarding
    taxa outright; both numbers are CLI parameters because the tradeoff is a judgement,
    not a constant.
    """
    return {
        c.taxon_id: min(c.photos, max_photos_per_taxon)
        for c in coverages
        if c.observations >= min_observations
    }


def photo_url(photo_id: int, extension: str, size: str = PHOTO_SIZE) -> str:
    """S3 path for a photo. Confirmed against the open-data documentation."""
    clean = str(extension).lstrip(".") or "jpg"
    return f"https://inaturalist-open-data.s3.amazonaws.com/photos/{photo_id}/{size}.{clean}"


def sample_photos(
    joined: pd.DataFrame,
    caps: dict[int, int],
    seed: int = 0,
) -> pd.DataFrame:
    """Take up to ``caps[taxon]`` photos per taxon, spreading across observations.

    Sampling observations before photos rather than photos directly: a taxon with 500
    photos from 3 observations is three individuals photographed repeatedly, and taking
    a flat photo sample would fill the class with near-duplicates of the same beetle.
    """
    selected: list[pd.DataFrame] = []
    for taxon_id, cap in caps.items():
        group = joined[joined["taxon_id"] == taxon_id]
        if group.empty:
            continue
        shuffled = group.sample(frac=1.0, random_state=seed)
        ordered = shuffled.assign(
            _rank=shuffled.groupby("observation_uuid").cumcount()
        ).sort_values(["_rank", "observation_uuid"], kind="stable")
        selected.append(ordered.head(cap).drop(columns="_rank"))

    if not selected:
        return joined.iloc[0:0].copy()
    return pd.concat(selected, ignore_index=True)
