"""Tests for CLI wiring — argument parsing and the stop-and-ask gate.

Not tested here: anything that touches GBIF or the iNaturalist archive. Those are
unreachable from the development sandbox and are exercised on Colab; the logic they
call is covered in test_gbif.py and test_inat.py.
"""

from __future__ import annotations

import pandas as pd
import pytest

from lifelist_train.cli import images as images_cli
from lifelist_train.cli import taxa as taxa_cli


def write_tables(tmp_path):
    """A directory of extracted tables, the shape read_table accepts."""
    pd.DataFrame(
        [
            ("obs-1", 100, "research", 55.68, 12.57),
            ("obs-2", 100, "research", 55.10, 14.90),
            ("obs-3", 101, "research", 56.16, 10.20),
            ("obs-4", 101, "needs_id", 56.16, 10.20),
        ],
        columns=["observation_uuid", "taxon_id", "quality_grade", "latitude", "longitude"],
    ).to_csv(tmp_path / "observations.csv", sep="\t", index=False)

    pd.DataFrame(
        [
            ("ph-1", 1, "obs-1", "jpg", "CC0"),
            ("ph-2", 2, "obs-2", "jpg", "CC-BY"),
            ("ph-3", 3, "obs-3", "jpg", "CC-BY-NC"),
            ("ph-4", 4, "obs-4", "jpg", "CC0"),
        ],
        columns=["photo_uuid", "photo_id", "observation_uuid", "extension", "license"],
    ).to_csv(tmp_path / "photos.csv", sep="\t", index=False)

    return tmp_path


# -- argument parsing -----------------------------------------------------------


def test_taxa_defaults():
    args = taxa_cli.build_parser().parse_args([])

    assert args.country == "DK"
    assert args.min_occurrences == 10
    assert not args.force


def test_taxa_accepts_a_country_override():
    assert taxa_cli.build_parser().parse_args(["--country", "SE"]).country == "SE"


def test_images_requires_an_archive():
    with pytest.raises(SystemExit):
        images_cli.build_parser().parse_args([])


def test_images_thresholds_are_configurable():
    args = images_cli.build_parser().parse_args(
        ["--archive", "x.tar.gz", "--thresholds", "20", "40"]
    )

    assert args.thresholds == [20, 40]


def test_bornholm_is_inside_the_default_box():
    """Regression guard: a box stopping at 13°E loses an island."""
    min_lat, min_lon, max_lat, max_lon = images_cli.DENMARK_BBOX

    assert min_lat <= 55.10 <= max_lat
    assert min_lon <= 14.90 <= max_lon


# -- table reading --------------------------------------------------------------


def test_read_table_from_a_directory(tmp_path):
    tables = write_tables(tmp_path)
    df = images_cli.read_table(tables, "observations")

    assert len(df) == 4
    assert "quality_grade" in df.columns


def test_read_table_reports_a_missing_file(tmp_path):
    with pytest.raises(FileNotFoundError, match="observations"):
        images_cli.read_table(tmp_path, "observations")


# -- the stop-and-ask gate ------------------------------------------------------


def test_run_without_commit_writes_nothing(tmp_path, capsys):
    tables = write_tables(tmp_path)
    cache = tmp_path / "cache"

    code = images_cli.main(
        ["--archive", str(tables), "--cache-dir", str(cache), "--min-observations", "1"]
    )

    assert code == 0
    assert not (cache / "photo_manifest.parquet").exists()
    assert "Re-run with --commit" in capsys.readouterr().out


def test_report_is_printed_before_any_decision(tmp_path, capsys):
    tables = write_tables(tmp_path)

    images_cli.main(
        [
            "--archive", str(tables),
            "--cache-dir", str(tmp_path / "cache"),
            "--thresholds", "1", "2",
            "--min-observations", "1",
        ]
    )
    out = capsys.readouterr().out

    assert "min observations" in out
    assert "not photos" in out


def test_commit_writes_the_manifest(tmp_path):
    tables = write_tables(tmp_path)
    cache = tmp_path / "cache"

    code = images_cli.main(
        [
            "--archive", str(tables),
            "--cache-dir", str(cache),
            "--min-observations", "1",
            "--commit",
        ]
    )

    assert code == 0
    manifest = pd.read_parquet(cache / "photo_manifest.parquet")
    assert len(manifest) == 3  # ph-4 belongs to a needs_id observation
    assert set(manifest["taxon_id"]) == {100, 101}


def test_needs_id_observations_never_reach_the_manifest(tmp_path):
    tables = write_tables(tmp_path)
    cache = tmp_path / "cache"

    images_cli.main(
        ["--archive", str(tables), "--cache-dir", str(cache), "--min-observations", "1", "--commit"]
    )
    manifest = pd.read_parquet(cache / "photo_manifest.parquet")

    assert 4 not in set(manifest["photo_id"])
