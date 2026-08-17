"""Shared CLI plumbing.

Every stage caches to a directory so a Colab disconnect costs one stage, never the run
(build plan §3). The cache is keyed by stage name only — deliberately simple, because a
content-hashed cache that silently misses is worse than an explicit `--force`.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path
from typing import Any

LOG = logging.getLogger("lifelist")


def add_common_args(parser: argparse.ArgumentParser) -> argparse.ArgumentParser:
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path("cache"),
        help="stage outputs land here; on Colab point this at Drive",
    )
    parser.add_argument("--force", action="store_true", help="recompute even if cached")
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def setup_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
        stream=sys.stderr,
    )


def cache_path(cache_dir: Path, name: str) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir / name


def load_json(path: Path) -> Any | None:
    if not path.exists():
        return None
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def write_json(path: Path, payload: Any) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    LOG.info("wrote %s", path)
    return path


def print_table(rows: list[tuple[str, ...]], headers: tuple[str, ...]) -> None:
    """Plain fixed-width table. The stop-and-ask reports are read by a human."""
    widths = [
        max(len(str(headers[i])), *(len(str(r[i])) for r in rows)) if rows else len(headers[i])
        for i in range(len(headers))
    ]
    line = "  ".join(h.ljust(widths[i]) for i, h in enumerate(headers))
    print(line)
    print("  ".join("-" * w for w in widths))
    for row in rows:
        print("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(row)))
