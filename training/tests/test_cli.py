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


def test_open_table_streams_chunks_from_a_directory(tmp_path):
    tables = write_tables(tmp_path)

    with images_cli.open_table(tables, "observations") as chunks:
        frames = list(chunks)

    assert sum(len(f) for f in frames) == 4
    assert "quality_grade" in frames[0].columns


def test_open_table_reports_a_missing_file(tmp_path):
    with (
        pytest.raises(FileNotFoundError, match="observations"),
        images_cli.open_table(tmp_path, "observations") as chunks,
    ):
        list(chunks)


def test_open_table_reads_a_tar_archive_without_extracting_it(tmp_path):
    """The uncompressed set is tens of gigabytes; it must never hit disk."""
    import tarfile

    tables = write_tables(tmp_path)
    archive = tmp_path / "bundle.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        for name in ("observations.csv", "photos.csv"):
            tar.add(tables / name, arcname=name)

    with images_cli.open_table(archive, "photos") as chunks:
        total = sum(len(f) for f in chunks)

    assert total == 4


def test_streaming_result_matches_a_whole_table_read(tmp_path):
    """Chunking must not change the answer — only the peak memory."""
    from lifelist_train.inat import filter_observation_chunks, filter_observations

    tables = write_tables(tmp_path)
    whole = pd.read_csv(tables / "observations.csv", sep="\t")

    with images_cli.open_table(tables, "observations") as chunks:
        streamed = filter_observation_chunks(chunks, images_cli.DENMARK_BBOX)

    direct = filter_observations(whole, images_cli.DENMARK_BBOX)

    assert set(streamed["observation_uuid"]) == set(direct["observation_uuid"])


def test_empty_result_is_treated_as_a_schema_problem(tmp_path, caplog):
    """Zero Danish observations means the schema moved, not that Denmark is empty."""
    pd.DataFrame(
        [("obs-1", 100, "research", 0.0, 0.0)],  # off the coast of Africa
        columns=["observation_uuid", "taxon_id", "quality_grade", "latitude", "longitude"],
    ).to_csv(tmp_path / "observations.csv", sep="\t", index=False)
    pd.DataFrame(
        [("ph-1", 1, "obs-1", "jpg", "CC0")],
        columns=["photo_uuid", "photo_id", "observation_uuid", "extension", "license"],
    ).to_csv(tmp_path / "photos.csv", sep="\t", index=False)

    code = images_cli.main(["--archive", str(tmp_path), "--cache-dir", str(tmp_path / "c")])

    assert code == 1
    assert "schema change" in caplog.text


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


# -- stage 1 concurrent fetch ---------------------------------------------------
#
# The network client is injected, so the ordering guarantee that leaf indices depend on
# can be asserted without touching GBIF.


class FakeClient:
    """A GbifClient-shaped stub with deliberately uneven latency.

    The sleeps are what make the test meaningful: with a thread pool, the fast keys
    finish first, so anything that appended results as they completed would come back
    reordered. Ordering by input is what keeps taxa_raw.json stable across runs.
    """

    def __init__(self, failing: set[int] | None = None):
        self.failing = failing or set()
        self.seen: list[int] = []

    def species(self, key: int) -> dict:
        import time

        time.sleep(0.02 if key % 2 else 0.001)
        self.seen.append(key)
        if key in self.failing:
            raise RuntimeError("boom")
        return {
            "key": key,
            "rank": "SPECIES",
            "canonicalName": f"Species {key}",
            "taxonomicStatus": "ACCEPTED",
            "genusKey": 900,
            "genus": "Genus",
        }

    def vernacular_names(self, key: int) -> list[dict]:
        return [{"language": "eng", "vernacularName": f"name {key}", "preferred": True}]

    def synonyms(self, key: int) -> list[dict]:
        return [
            {
                "canonicalName": f"Synonym {key}",
                "taxonomicStatus": "SYNONYM",
                "acceptedKey": key,
            }
        ]


def test_fetch_taxa_preserves_input_order_under_concurrency():
    keys = [(k, 100 - k) for k in range(1, 21)]

    taxa, _, skipped = taxa_cli.fetch_taxa(FakeClient(), keys, workers=8)

    assert skipped == 0
    assert [t["key"] for t in taxa] == [k for k, _ in keys]


def test_fetch_taxa_is_identical_serial_and_concurrent():
    keys = [(k, 100 - k) for k in range(1, 21)]

    serial, serial_synonyms, _ = taxa_cli.fetch_taxa(FakeClient(), keys, workers=1)
    concurrent, concurrent_synonyms, _ = taxa_cli.fetch_taxa(FakeClient(), keys, workers=8)

    assert serial == concurrent
    assert serial_synonyms == concurrent_synonyms


def test_fetch_taxa_skips_a_failing_key_without_ending_the_run():
    keys = [(k, 10) for k in range(1, 6)]

    taxa, _, skipped = taxa_cli.fetch_taxa(FakeClient(failing={3}), keys, workers=4)

    assert skipped == 1
    assert [t["key"] for t in taxa] == [1, 2, 4, 5]


def test_fetch_taxon_can_skip_vernaculars():
    taxon, _ = taxa_cli.fetch_taxon(FakeClient(), 7, 42, with_vernaculars=False)

    assert taxon is not None
    assert taxon["vernacular_en"] is None
    assert taxon["occurrences"] == 42


def test_taxa_workers_default():
    assert taxa_cli.build_parser().parse_args([]).workers == 8


def test_gbif_client_only_talks_to_the_injected_session():
    from lifelist_train.gbif import GbifClient

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"ok": True}

    class FakeSession:
        def __init__(self):
            self.calls = []

        def get(self, url, params=None, timeout=None):
            self.calls.append((url, params))
            return FakeResponse()

    session = FakeSession()
    client = GbifClient(pause_s=0, session=session)

    assert client.species(123) == {"ok": True}
    assert session.calls[0][0].endswith("/species/123")
