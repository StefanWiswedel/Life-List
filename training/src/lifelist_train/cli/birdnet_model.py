"""Fetch the pinned BirdNET asset and refuse anything that is not it.

    lifelist-birdnet-model --out app/src/main/assets/birdnet.onnx -v

The 149 MB model is not in the repository, for the same reason the 335 MB vision graph is not:
a binary somebody once made and nobody can regenerate is not a source of truth. CI fetches it on
every tagged build and checks it against `shared/birdnet/models.lock.json`.

**The digest is pinned in the repository, not read from the remote manifest.** Trusting the
manifest served alongside the file verifies nothing — a mirror that swapped the model would swap
its manifest in the same breath. Pinned here, a changed file fails the build, and a deliberate
upgrade is a commit that shows up in a diff. BUILD.md §3.1 already asks for exactly that: a
BirdNET version bump is a deliberate revalidation, never a drift.

A mismatch deletes what it downloaded. Half-verified bytes on disk are how a bad asset gets
picked up by the next run that skips the check.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any

from ._common import LOG, setup_logging

LOCK = Path(__file__).resolve().parents[4] / "shared" / "birdnet" / "models.lock.json"
CHUNK = 1 << 20


class DigestMismatch(RuntimeError):
    """The bytes are not the bytes we pinned. Never recoverable by retrying."""


def digest_of(path: Path, chunk: int = CHUNK) -> str:
    """SHA-256, streamed — the model is 149 MB and does not belong in memory to be hashed."""
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(chunk), b""):
            h.update(block)
    return h.hexdigest()


def verify(path: Path, expected: str, expected_bytes: int | None = None) -> None:
    """Raise unless `path` is exactly the pinned artefact."""
    actual_bytes = path.stat().st_size
    if expected_bytes is not None and actual_bytes != expected_bytes:
        raise DigestMismatch(
            f"{path.name} is {actual_bytes} bytes, expected {expected_bytes}"
        )
    actual = digest_of(path)
    if actual != expected:
        raise DigestMismatch(
            f"{path.name} hashes to {actual}, expected {expected}. "
            "The pinned asset changed under us; this is not a retry."
        )


def fetch(
    url: str,
    out: Path,
    expected: str,
    expected_bytes: int | None = None,
    opener: Callable[[str], Any] = urllib.request.urlopen,
) -> Path:
    """Download to a temporary neighbour, verify, then move into place.

    Never straight to `out`: an interrupted download that lands on the final name looks, to
    every later step, exactly like a good one.
    """
    out.parent.mkdir(parents=True, exist_ok=True)
    staging = out.with_suffix(out.suffix + ".part")
    with opener(url) as response, staging.open("wb") as handle:
        while block := response.read(CHUNK):
            handle.write(block)
    try:
        verify(staging, expected, expected_bytes)
    except DigestMismatch:
        staging.unlink(missing_ok=True)
        raise
    staging.replace(out)
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--lock", type=Path, default=LOCK)
    ap.add_argument("--out", type=Path, required=True, help="where the .onnx should land")
    ap.add_argument("--which", default="model", choices=("model", "labels"))
    ap.add_argument(
        "--check-only",
        action="store_true",
        help="verify a file already on disk and download nothing",
    )
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    setup_logging(args.verbose)

    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    entry = lock["files"][args.which]
    LOG.info(
        "BirdNET %s (%s), %s: %.1f MB",
        lock["birdnet_version"],
        lock["region"],
        args.which,
        entry["bytes"] / 1e6,
    )

    if args.check_only:
        if not args.out.exists():
            LOG.error("%s does not exist", args.out)
            return 1
        verify(args.out, entry["sha256"], entry["bytes"])
        LOG.info("%s matches the pinned digest", args.out)
        return 0

    if args.out.exists():
        try:
            verify(args.out, entry["sha256"], entry["bytes"])
        except DigestMismatch:
            LOG.warning("%s is not the pinned asset; re-fetching", args.out)
        else:
            LOG.info("%s is already the pinned asset", args.out)
            return 0

    LOG.info("fetching %s", entry["url"])
    fetch(entry["url"], args.out, entry["sha256"], entry["bytes"])
    LOG.info("wrote %s, verified", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
