"""Stage 3 — BioCLIP embeddings, in resumable shards.

Everything here that decides anything is a pure function over a manifest, so the
sharding, resume and failure logic is testable without a GPU, a network, or a single
real JPEG. The two things that cannot be — fetching bytes and running the model — are
injected as callables, which is the same shape `GbifClient` gives stage 1.

Why shards. Embedding 334k photos is hours of downloading from S3 with a model in the
loop, on a free Colab runtime that can vanish at 12 hours or sooner, or on a laptop
someone might close. A run that keeps everything in memory and writes at the end is a
run that loses a night's work to one disconnect. Each shard is written atomically and
skipped on the next pass, so an interruption costs one shard.

Photos are ordered by `photo_id` before sharding, not left in manifest order. Shard
boundaries then depend only on the manifest's contents, so resuming after a re-run of
stage 2 lands on the same boundaries rather than silently re-embedding everything.
"""

from __future__ import annotations

import logging
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

LOG = logging.getLogger("lifelist")

SHARD_GLOB = "shard-*.npz"
EMBED_DIM = 512  # BioCLIP v1 ViT-B/16, verified in VERIFICATION.md §2


@dataclass(frozen=True, slots=True)
class Shard:
    """One unit of work: a contiguous slice of the ordered manifest."""

    index: int
    rows: pd.DataFrame

    @property
    def name(self) -> str:
        return f"shard-{self.index:05d}.npz"

    def __len__(self) -> int:
        return len(self.rows)


def order_manifest(manifest: pd.DataFrame) -> pd.DataFrame:
    """Deterministic order, independent of how stage 2 happened to concatenate taxa."""
    return manifest.sort_values("photo_id", kind="stable").reset_index(drop=True)


def plan_shards(manifest: pd.DataFrame, shard_size: int = 4000) -> list[Shard]:
    if shard_size < 1:
        raise ValueError("shard_size must be at least 1")
    ordered = order_manifest(manifest)
    return [
        Shard(index=i, rows=ordered.iloc[start : start + shard_size].reset_index(drop=True))
        for i, start in enumerate(range(0, len(ordered), shard_size))
    ]


def pending_shards(shards: Sequence[Shard], out_dir: Path) -> list[Shard]:
    """Shards with no output file yet.

    Presence is the completion signal, and writes are atomic (see `write_shard`), so a
    file that exists is a file that finished. A half-written shard from a hard kill
    would otherwise be indistinguishable from a good one, and the failure mode —
    silently truncated training data — is invisible until accuracy is mysteriously bad.

    Correct only while the manifest is unchanged. `plan_remaining` is what a run should
    actually use; this stays because shard-file presence is still the thing that decides
    whether a *shard* finished.
    """
    return [s for s in shards if not (out_dir / s.name).exists()]


def resolved_photo_ids(out_dir: Path, include_failures: bool = True) -> set[int]:
    """Photo ids already dealt with: embedded, or recorded as permanently failed.

    Only the id arrays are read, not the embeddings, so this stays cheap across a
    hundred shards.
    """
    resolved: set[int] = set()
    if not out_dir.exists():
        return resolved
    for path in sorted(out_dir.glob(SHARD_GLOB)):
        with np.load(path, allow_pickle=False) as data:
            resolved.update(int(x) for x in data["photo_id"])
            if include_failures and "failed_photo_id" in data:
                resolved.update(int(x) for x in data["failed_photo_id"])
    return resolved


def next_shard_index(out_dir: Path) -> int:
    """One past the highest shard already written, so new shards never collide."""
    if not out_dir.exists():
        return 0
    indices = []
    for path in out_dir.glob(SHARD_GLOB):
        try:
            indices.append(int(path.stem.split("-")[1]))
        except (IndexError, ValueError):
            continue
    return max(indices) + 1 if indices else 0


def plan_remaining(
    manifest: pd.DataFrame,
    shard_size: int,
    out_dir: Path,
    retry_failures: bool = False,
) -> list[Shard]:
    """Shard only the photos that still need embedding.

    Resuming by *photo id* rather than by shard file is what makes the photo cap a
    reversible decision. Raising `--max-photos-per-taxon` from 150 to 300 produces a
    strict superset of the same manifest (same seed, same sampling order), so a top-up
    should embed only the 211k new photos. Planning by shard index instead would
    renumber every boundary and re-embed all 334k already done.

    On an untouched output directory this returns exactly `plan_shards`, so a first run
    is unaffected and its boundaries still depend only on the manifest.
    """
    resolved = resolved_photo_ids(out_dir, include_failures=not retry_failures)
    remaining = manifest[~manifest["photo_id"].isin(resolved)]
    offset = next_shard_index(out_dir)
    return [
        Shard(index=shard.index + offset, rows=shard.rows)
        for shard in plan_shards(remaining, shard_size)
    ]


def write_shard(
    out_dir: Path,
    shard: Shard,
    embeddings: np.ndarray,
    photo_ids: Sequence[int],
    failures: Sequence[tuple[int, str]] = (),
) -> Path:
    """Write one shard atomically: temp file, then rename."""
    if len(embeddings) != len(photo_ids):
        raise ValueError(f"{len(embeddings)} embeddings for {len(photo_ids)} photos")

    out_dir.mkdir(parents=True, exist_ok=True)
    # drop_duplicates before the lookup: `.loc[list]` against a duplicated index returns
    # *every* matching row, so one repeated photo_id silently lengthened taxon_id and
    # observation_uuid past photo_id and embedding, and the shard failed to load later.
    # The manifest should not contain duplicates (see `drop_ambiguous_photos`), but a
    # writer that corrupts its own output when it does is not a writer worth having.
    lookup = shard.rows.drop_duplicates("photo_id").set_index("photo_id")
    keep = lookup.loc[list(photo_ids)].reset_index()

    destination = out_dir / shard.name
    temporary = destination.with_suffix(".npz.tmp")
    # Written through a file handle rather than a path: np.savez_compressed appends
    # ".npz" to any path that lacks it, which would silently defeat the temp-then-rename
    # and leave half-written shards looking finished.
    with temporary.open("wb") as handle:
        np.savez_compressed(
            handle,
            photo_id=np.asarray(photo_ids, dtype=np.int64),
            taxon_id=keep["taxon_id"].to_numpy(dtype=np.int64),
            observation_uuid=keep["observation_uuid"].to_numpy(dtype=object).astype("U36"),
            embedding=np.asarray(embeddings, dtype=np.float16),
            failed_photo_id=np.asarray([f for f, _ in failures], dtype=np.int64),
            failed_reason=np.asarray([r for _, r in failures], dtype=object).astype("U120"),
        )
    temporary.rename(destination)
    return destination


def load_shards(out_dir: Path) -> pd.DataFrame:
    """Read every completed shard back as one frame, embeddings included."""
    frames: list[pd.DataFrame] = []
    for path in sorted(out_dir.glob(SHARD_GLOB)):
        with np.load(path, allow_pickle=False) as data:
            frames.append(
                pd.DataFrame(
                    {
                        "photo_id": data["photo_id"],
                        "taxon_id": data["taxon_id"],
                        "observation_uuid": data["observation_uuid"],
                        "embedding": list(data["embedding"]),
                    }
                )
            )
    if not frames:
        return pd.DataFrame(
            columns=["photo_id", "taxon_id", "observation_uuid", "embedding"]
        )
    return pd.concat(frames, ignore_index=True)


def shard_failures(out_dir: Path) -> pd.DataFrame:
    """Every photo that could not be fetched or decoded, across all shards."""
    ids: list[int] = []
    reasons: list[str] = []
    for path in sorted(out_dir.glob(SHARD_GLOB)):
        with np.load(path, allow_pickle=False) as data:
            if "failed_photo_id" in data:
                ids.extend(data["failed_photo_id"].tolist())
                reasons.extend(data["failed_reason"].tolist())
    return pd.DataFrame({"photo_id": ids, "reason": reasons})


def l2_normalise(embeddings: np.ndarray) -> np.ndarray:
    """Unit-length rows.

    The linear head and the audio/image fusion both assume unit vectors — cosine
    similarity is a dot product only if this holds. Normalising once here beats
    normalising in three places later and getting one of them wrong.
    """
    matrix = np.asarray(embeddings, dtype=np.float32)
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return matrix / norms


def embed_shard(
    shard: Shard,
    fetch: Callable[[int, str], bytes],
    encode: Callable[[list[bytes]], np.ndarray],
    batch_size: int = 64,
    on_progress: Callable[[int, int], None] | None = None,
) -> tuple[np.ndarray, list[int], list[tuple[int, str]]]:
    """Fetch and encode one shard, tolerating individual photo failures.

    A dead photo id is normal — iNaturalist observations get deleted and licences get
    changed after the monthly snapshot. Losing one photo must not lose the shard, so
    failures are collected and stored alongside the embeddings rather than raised.
    """
    vectors: list[np.ndarray] = []
    kept: list[int] = []
    failures: list[tuple[int, str]] = []

    batch_bytes: list[bytes] = []
    batch_ids: list[int] = []

    def flush() -> None:
        if not batch_bytes:
            return
        vectors.append(l2_normalise(encode(batch_bytes)))
        kept.extend(batch_ids)
        batch_bytes.clear()
        batch_ids.clear()

    for position, row in enumerate(shard.rows.itertuples(index=False), start=1):
        try:
            batch_bytes.append(fetch(int(row.photo_id), str(row.extension)))
            batch_ids.append(int(row.photo_id))
        except Exception as exc:  # noqa: BLE001 — a dead photo must not kill the shard
            failures.append((int(row.photo_id), f"{type(exc).__name__}: {exc}"[:120]))

        if len(batch_bytes) >= batch_size:
            flush()
        if on_progress is not None and position % 500 == 0:
            on_progress(position, len(shard))

    flush()

    if not vectors:
        return np.zeros((0, EMBED_DIM), dtype=np.float32), [], failures
    return np.concatenate(vectors, axis=0), kept, failures


def coverage_summary(manifest: pd.DataFrame, out_dir: Path) -> dict[str, int]:
    """What a resumed run should report before doing anything."""
    shards = plan_shards(manifest, shard_size=1)  # only used for the total
    done = len(list(out_dir.glob(SHARD_GLOB))) if out_dir.exists() else 0
    return {
        "photos": len(manifest),
        "taxa": int(manifest["taxon_id"].nunique()) if len(manifest) else 0,
        "shards_complete": done,
        "photos_total": len(shards),
    }


def missing_photos(manifest: pd.DataFrame, out_dir: Path) -> Iterable[int]:
    """Photo ids in the manifest with neither an embedding nor a recorded failure."""
    embedded = set(load_shards(out_dir)["photo_id"].tolist())
    failed = set(shard_failures(out_dir)["photo_id"].tolist())
    return (int(p) for p in manifest["photo_id"] if int(p) not in embedded and int(p) not in failed)
