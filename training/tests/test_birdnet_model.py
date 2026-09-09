"""Fetching the pinned BirdNET asset, and refusing anything else."""

from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path

import pytest

from lifelist_train.cli.birdnet_model import DigestMismatch, digest_of, fetch, main, verify

PAYLOAD = b"not really a neural network" * 100
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()


def opener_for(payload: bytes):
    class Response(io.BytesIO):
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            self.close()
            return False

    return lambda url: Response(payload)


def test_a_matching_download_lands_at_the_final_name(tmp_path: Path):
    out = tmp_path / "birdnet.onnx"

    fetch("https://example.invalid/m", out, DIGEST, len(PAYLOAD), opener_for(PAYLOAD))

    assert out.read_bytes() == PAYLOAD
    assert digest_of(out) == DIGEST


def test_a_mismatched_download_never_reaches_the_final_name(tmp_path: Path):
    """An interrupted or swapped download that lands on the final name looks, to every later
    step, exactly like a good one — so it is staged, verified, and only then moved."""
    out = tmp_path / "birdnet.onnx"

    with pytest.raises(DigestMismatch):
        fetch("https://example.invalid/m", out, DIGEST, len(PAYLOAD), opener_for(b"something else"))

    assert not out.exists()
    assert not list(tmp_path.glob("*.part")), "the staged file must be cleaned up"


def test_the_wrong_length_is_caught_before_the_hash_is_computed(tmp_path: Path):
    """Cheap check first: hashing 149 MB to learn it is 12 bytes is a waste of a build."""
    out = tmp_path / "birdnet.onnx"
    out.write_bytes(PAYLOAD)

    with pytest.raises(DigestMismatch, match="bytes"):
        verify(out, DIGEST, expected_bytes=len(PAYLOAD) + 1)


def lockfile(tmp_path: Path, digest: str = DIGEST) -> Path:
    lock = tmp_path / "models.lock.json"
    lock.write_text(
        json.dumps(
            {
                "birdnet_version": "3.0-preview3.1",
                "region": "western-palearctic",
                "files": {
                    "model": {
                        "url": "https://example.invalid/m",
                        "sha256": digest,
                        "bytes": len(PAYLOAD),
                    }
                },
            }
        ),
        encoding="utf-8",
    )
    return lock


def test_check_only_passes_a_file_that_is_already_right(tmp_path: Path):
    out = tmp_path / "birdnet.onnx"
    out.write_bytes(PAYLOAD)

    assert main(["--lock", str(lockfile(tmp_path)), "--out", str(out), "--check-only"]) == 0


def test_check_only_fails_when_the_file_is_missing(tmp_path: Path):
    assert main(
        ["--lock", str(lockfile(tmp_path)), "--out", str(tmp_path / "nope.onnx"), "--check-only"]
    ) == 1


def test_check_only_refuses_a_file_that_is_not_the_pinned_one(tmp_path: Path):
    out = tmp_path / "birdnet.onnx"
    out.write_bytes(PAYLOAD)

    with pytest.raises(DigestMismatch):
        main([
            "--lock", str(lockfile(tmp_path, digest="0" * 64)),
            "--out", str(out),
            "--check-only",
        ])


def test_the_shipped_lock_file_pins_a_digest_for_everything_it_names():
    """A lock file with an entry and no digest is worse than no lock file: it looks checked."""
    from lifelist_train.cli.birdnet_model import LOCK

    lock = json.loads(LOCK.read_text(encoding="utf-8"))

    assert lock["files"], "the lock file names nothing"
    for name, entry in lock["files"].items():
        assert len(entry["sha256"]) == 64, f"{name} has no usable digest"
        assert entry["bytes"] > 0, f"{name} has no size"
        assert entry["url"].startswith("https://"), f"{name} is not fetched over https"
