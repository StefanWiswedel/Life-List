#!/usr/bin/env python3
"""Stage 2 for a disk that cannot hold the whole archive.

`lifelist-images --archive inaturalist-open-data-latest.tar.gz` needs the 34 GB tarball
present before it reads a row. As of 27 July 2026 the archive is 34.2 GB compressed —
see VERIFICATION.md §7, which corrects the 4.5 GB figure BUILD.md inherited from 2021.

The bucket also publishes each table separately, and `cli/images.py` finishes with
observations before it opens photos. So the two never need to coexist:

    observations.csv.gz  12.7 GB  ->  filter  ->  cache Danish subset  ->  delete
    photos.csv.gz        19.6 GB  ->  filter  ->  cache               ->  delete

Peak disk 20 GB rather than 34 GB, and an interrupted download costs one table.

Identical logic to the CLI — the same `filter_observation_chunks`, `filter_photo_chunks`,
`coverage` and `DENMARK_BBOX`. Only the I/O ordering differs. Like the CLI, it prints the
threshold table and stops: the taxon count is a human decision.

    python tools/stage2_sequenced.py --work-dir /tmp/inat --cache-dir cache

Measured on a cloud container at ~65 MB/s: observations 3 min download + 13 min filter,
photos 4 min download + ~2 h filter. Budget three hours.
"""

from __future__ import annotations

import argparse
import logging
import os
import subprocess
import time
from pathlib import Path

import pandas as pd

from lifelist_train.cli.images import DENMARK_BBOX
from lifelist_train.inat import (
    DEFAULT_CHUNK_SIZE,
    coverage,
    filter_observation_chunks,
    filter_photo_chunks,
    join_photos_to_taxa,
    select_taxa,
    taxa_at_thresholds,
)

BASE = "https://inaturalist-open-data.s3.amazonaws.com"
LOG = logging.getLogger("stage2-sequenced")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=Path("inat-work"),
        help="where the .csv.gz tables are downloaded and deleted again",
    )
    parser.add_argument("--cache-dir", type=Path, default=Path("cache"))
    parser.add_argument("--thresholds", type=int, nargs="+", default=[50, 80, 120, 200])
    parser.add_argument("--max-photos-per-taxon", type=int, default=500)
    parser.add_argument(
        "--keep",
        action="store_true",
        help="do not delete each table after filtering it",
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def free_gb(path: Path) -> float:
    st = os.statvfs(path)
    return st.f_bavail * st.f_frsize / 1e9


def fetch(work_dir: Path, name: str) -> Path:
    dest = work_dir / name
    if dest.exists():
        LOG.info("%s already present (%.1f GB)", name, dest.stat().st_size / 1e9)
        return dest
    LOG.info("downloading %s (%.1f GB free)", name, free_gb(work_dir))
    started = time.time()
    subprocess.run(["curl", "-sS", "--fail", "-o", str(dest), f"{BASE}/{name}"], check=True)
    LOG.info(
        "%s: %.1f GB in %.0f s (%.1f GB free)",
        name,
        dest.stat().st_size / 1e9,
        time.time() - started,
        free_gb(work_dir),
    )
    return dest


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )
    args.work_dir.mkdir(parents=True, exist_ok=True)
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    reader = {"sep": "\t", "chunksize": DEFAULT_CHUNK_SIZE, "low_memory": False}

    observations_gz = fetch(args.work_dir, "observations.csv.gz")
    LOG.info("streaming observations through the Denmark/research-grade filter")
    started = time.time()
    with pd.read_csv(observations_gz, **reader) as chunks:
        observations = filter_observation_chunks(chunks, DENMARK_BBOX)
    LOG.info(
        "%d research-grade Danish observations in %.0f s",
        len(observations),
        time.time() - started,
    )

    if observations.empty:
        LOG.error(
            "no observations survived the filter. That is far more likely to be a schema "
            "change than a fact about Denmark — check the table headers before adjusting "
            "the filter to fit."
        )
        return 1

    observations.to_parquet(args.cache_dir / "stage2_observations_dk.parquet", index=False)
    if not args.keep:
        observations_gz.unlink()
        LOG.info("observations cached, archive deleted (%.1f GB free)", free_gb(args.work_dir))

    wanted = set(observations["observation_uuid"])
    photos_gz = fetch(args.work_dir, "photos.csv.gz")
    LOG.info("streaming photos through the licence + uuid filter (this is the slow one)")
    started = time.time()
    with pd.read_csv(photos_gz, **reader) as chunks:
        photos = filter_photo_chunks(chunks, wanted)
    LOG.info("%d openly licensed photos in %.0f s", len(photos), time.time() - started)
    photos.to_parquet(args.cache_dir / "stage2_photos_dk.parquet", index=False)
    if not args.keep:
        photos_gz.unlink()
        LOG.info("photos cached, archive deleted (%.1f GB free)", free_gb(args.work_dir))

    joined = join_photos_to_taxa(observations, photos)
    joined.to_parquet(args.cache_dir / "stage2_joined.parquet", index=False)
    coverages = coverage(joined)
    LOG.info("%d taxa with at least one usable photo", len(coverages))

    thresholds = tuple(args.thresholds)
    counts = taxa_at_thresholds(coverages, thresholds)

    lines = [
        "",
        "Taxa surviving each minimum-observation threshold",
        "(observations, not photos — ten photos of one beetle is one piece of evidence)",
        "",
        f"{'min observations':>18}  {'taxa':>8}  {'photos':>12}",
        f"{'-' * 18}  {'-' * 8}  {'-' * 12}",
    ]
    for threshold in thresholds:
        selected = select_taxa(coverages, threshold, args.max_photos_per_taxon)
        lines.append(f"{threshold:>18}  {counts[threshold]:>8}  {sum(selected.values()):>12}")
    lines += [
        "",
        "Stopping here. This number sets the model's output dimension and every",
        "accuracy figure downstream, so it is worth a human decision.",
    ]
    report = "\n".join(lines)
    print(report)
    (args.cache_dir / "stage2_threshold_report.txt").write_text(report + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
