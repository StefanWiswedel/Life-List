"""Download Denmark's national species register.

    lifelist-register --out cache/danish_register.json -v

About 3 MB from arter.dk, unzipped in memory, reduced to the two sets the checklist filter
needs. See `lifelist_train.register` for why the rule has three clauses and VERIFICATION.md
section 90 for what it drops.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import zipfile
from pathlib import Path

from ..register import ARCHIVE_URL, read_archive
from ._common import LOG, add_common_args, setup_logging

USER_AGENT = "LifeList/0.16 (https://github.com/StefanWiswedel/Life-List)"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("cache/danish_register.json"))
    parser.add_argument("--url", default=ARCHIVE_URL)
    parser.add_argument(
        "--archive",
        type=Path,
        default=None,
        help="a zip already on disk, instead of fetching one",
    )
    return add_common_args(parser)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    if args.archive:
        blob = args.archive.read_bytes()
        LOG.info("read %s (%.1f MB)", args.archive, len(blob) / 1e6)
    else:
        import requests

        LOG.info("fetching %s", args.url)
        response = requests.get(args.url, headers={"User-Agent": USER_AGENT}, timeout=300)
        response.raise_for_status()
        blob = response.content
        LOG.info("%.1f MB", len(blob) / 1e6)

    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        if "taxon.txt" not in archive.namelist():
            LOG.error("no taxon.txt in the archive — it holds %s", archive.namelist())
            return 1
        with archive.open("taxon.txt") as handle:
            text = io.TextIOWrapper(handle, encoding="utf-8", newline="")
            csv.field_size_limit(10_000_000)
            register = read_archive(csv.reader(text, delimiter="\t"))

    if not register.usable:
        # Loudly, and without writing: a register that vouches for nothing would pass zero
        # species on the next checklist run and the log would read like a fact about Denmark.
        LOG.error(
            "only %d names and %d genera — that is a broken download, not a small country",
            len(register.names), len(register.genera),
        )
        return 1

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(
            {
                "source": args.url,
                "names": sorted(register.names),
                "genera": sorted(register.genera),
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    LOG.info(
        "%s: %d names, %d genera",
        args.out, len(register.names), len(register.genera),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
