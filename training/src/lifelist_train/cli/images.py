"""Stage 2 — filter the iNaturalist open-data tables and report coverage.

    lifelist-images --archive inaturalist-open-data-latest.tar.gz --cache-dir cache

This is the stage that **stops and asks**. The taxon count determines the model's output
dimension, the APK size, and every accuracy number downstream, so the threshold report is
printed and the run halts unless `--commit` is passed (build plan §8, step 4).
"""

from __future__ import annotations

import argparse
import tarfile
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

import pandas as pd

from ..inat import (
    DEFAULT_CHUNK_SIZE,
    coverage,
    filter_observation_chunks,
    filter_photo_chunks,
    join_photos_to_taxa,
    sample_photos,
    select_taxa,
    taxa_at_thresholds,
)
from ._common import LOG, add_common_args, cache_path, print_table, setup_logging, write_json

# Mainland plus islands. Bornholm sits near 15°E — a box stopping at 13 loses an island.
DENMARK_BBOX = (54.5, 8.0, 57.8, 15.2)

TABLE_FILES = {
    "observations": "observations.csv",
    "photos": "photos.csv",
}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Filter iNaturalist open data for Denmark")
    parser.add_argument(
        "--archive",
        type=Path,
        required=True,
        help="inaturalist-open-data-latest.tar.gz, or a directory of already-extracted tables",
    )
    parser.add_argument("--min-observations", type=int, default=80)
    parser.add_argument("--max-photos-per-taxon", type=int, default=500)
    parser.add_argument(
        "--thresholds",
        type=int,
        nargs="+",
        default=[50, 80, 120, 200],
        help="minimums to report before committing",
    )
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument(
        "--commit",
        action="store_true",
        help="write the photo manifest. Without this the run stops after the report.",
    )
    return add_common_args(parser)


@contextmanager
def open_table(archive: Path, table: str) -> Iterator[Iterator[pd.DataFrame]]:
    """Yield an iterator of chunks for one table, from an archive or a directory.

    Chunked because the tables do not fit in memory anywhere: as of 2021 the archive held
    42M observations and 70M photos, and it has grown several-fold since. Reading whole
    tables would fail on a laptop and on a Colab high-RAM runtime alike.

    The tar member is streamed rather than extracted, so no uncompressed copy is ever
    written to disk — which matters when the uncompressed set is tens of gigabytes.
    """
    filename = TABLE_FILES[table]
    reader_kwargs = {"sep": "\t", "chunksize": DEFAULT_CHUNK_SIZE, "low_memory": False}

    if archive.is_dir():
        for candidate in (archive / filename, archive / f"{filename}.gz"):
            if candidate.exists():
                LOG.info("streaming %s", candidate)
                with pd.read_csv(candidate, **reader_kwargs) as reader:
                    yield reader
                return
        raise FileNotFoundError(f"{filename} not found in {archive}")

    LOG.info("streaming %s from %s", filename, archive)
    with tarfile.open(archive, "r:*") as tar:
        member = next(
            (m for m in tar.getmembers() if Path(m.name).name.startswith(filename)),
            None,
        )
        if member is None:
            raise FileNotFoundError(f"{filename} not found in {archive}")
        handle = tar.extractfile(member)
        if handle is None:
            raise OSError(f"could not read {member.name}")
        with pd.read_csv(handle, **reader_kwargs) as reader:
            yield reader


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    with open_table(args.archive, "observations") as chunks:
        observations = filter_observation_chunks(chunks, DENMARK_BBOX)
    LOG.info("%d research-grade Danish observations", len(observations))

    if observations.empty:
        LOG.error(
            "no observations survived the filter. That is far more likely to be a schema "
            "change than a fact about Denmark — check the table headers before adjusting "
            "the filter to fit."
        )
        return 1

    wanted = set(observations["observation_uuid"])
    with open_table(args.archive, "photos") as chunks:
        photos = filter_photo_chunks(chunks, wanted)
    LOG.info("%d openly licensed photos of those observations", len(photos))

    joined = join_photos_to_taxa(observations, photos)
    coverages = coverage(joined)
    LOG.info("%d taxa with at least one usable photo", len(coverages))

    counts = taxa_at_thresholds(coverages, tuple(args.thresholds))

    print()
    print("Taxa surviving each minimum-observation threshold")
    print("(observations, not photos — ten photos of one beetle is one piece of evidence)")
    print()
    print_table(
        [
            (str(threshold), str(counts[threshold]))
            for threshold in sorted(counts, reverse=False)
        ],
        headers=("min observations", "taxa"),
    )
    print()

    selected = select_taxa(coverages, args.min_observations, args.max_photos_per_taxon)
    print(
        f"At --min-observations {args.min_observations}: "
        f"{len(selected)} taxa, {sum(selected.values())} photos."
    )

    if not args.commit:
        print()
        print("Stopping here. This number sets the model's output dimension and every")
        print("accuracy figure downstream, so it is worth a human decision.")
        print("Re-run with --commit once you have picked a threshold.")
        return 0

    manifest = sample_photos(joined, selected, seed=args.seed)
    manifest_path = cache_path(args.cache_dir, "photo_manifest.parquet")
    manifest.to_parquet(manifest_path, index=False)
    LOG.info("wrote %s (%d photos)", manifest_path, len(manifest))

    write_json(
        cache_path(args.cache_dir, "stage2_report.json"),
        {
            "thresholds": {str(k): v for k, v in counts.items()},
            "min_observations": args.min_observations,
            "max_photos_per_taxon": args.max_photos_per_taxon,
            "selected_taxa": len(selected),
            "selected_photos": int(len(manifest)),
        },
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
