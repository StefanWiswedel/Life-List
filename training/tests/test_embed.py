"""Tests for stage 3's sharding, resume and failure handling.

No torch, no GPU, no network. The fetcher and the encoder are injected, which is the
whole reason `embed.py` splits them out — the parts that decide what gets embedded and
what happens when a photo is missing are the parts that can silently corrupt a training
set, and they are all pure.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from lifelist_train.embed import (
    EMBED_DIM,
    embed_shard,
    l2_normalise,
    load_shards,
    missing_photos,
    pending_shards,
    plan_shards,
    shard_failures,
    write_shard,
)


def manifest(n: int = 10) -> pd.DataFrame:
    """A manifest deliberately not in photo_id order."""
    ids = list(reversed(range(100, 100 + n)))
    return pd.DataFrame(
        {
            "photo_uuid": [f"pu-{i}" for i in ids],
            "photo_id": ids,
            "observation_uuid": [f"obs-{i // 3}" for i in ids],
            "extension": ["jpg"] * n,
            "license": ["CC0"] * n,
            "taxon_id": [1000 + (i % 4) for i in ids],
        }
    )


def fake_encode(batch: list[bytes]) -> np.ndarray:
    """Deterministic vectors so a round trip can be checked exactly."""
    return np.stack([np.full(EMBED_DIM, float(len(raw)), dtype=np.float32) for raw in batch])


def fake_fetch(photo_id: int, extension: str) -> bytes:
    return b"x" * (photo_id % 7 + 1)


# -- sharding -------------------------------------------------------------------


def test_shards_are_ordered_by_photo_id_not_manifest_order():
    shards = plan_shards(manifest(10), shard_size=4)

    assert [len(s) for s in shards] == [4, 4, 2]
    assert shards[0].rows["photo_id"].tolist() == [100, 101, 102, 103]


def test_shard_boundaries_are_stable_across_row_shuffles():
    original = manifest(10)
    shuffled = original.sample(frac=1.0, random_state=7)

    a = plan_shards(original, 3)
    b = plan_shards(shuffled, 3)

    assert [s.rows["photo_id"].tolist() for s in a] == [s.rows["photo_id"].tolist() for s in b]


def test_shard_size_must_be_positive():
    with pytest.raises(ValueError):
        plan_shards(manifest(4), shard_size=0)


# -- resume ---------------------------------------------------------------------


def test_pending_skips_shards_already_on_disk(tmp_path):
    shards = plan_shards(manifest(10), 4)
    embeddings, kept, failures = embed_shard(shards[0], fake_fetch, fake_encode, batch_size=2)
    write_shard(tmp_path, shards[0], embeddings, kept, failures)

    remaining = pending_shards(shards, tmp_path)

    assert [s.index for s in remaining] == [1, 2]


def test_a_partial_write_is_not_mistaken_for_a_finished_shard(tmp_path):
    """The rename is the commit. A .tmp left by a kill must not count as done."""
    shards = plan_shards(manifest(10), 4)
    (tmp_path / "shard-00000.npz.tmp").write_bytes(b"truncated garbage")

    assert [s.index for s in pending_shards(shards, tmp_path)] == [0, 1, 2]


def test_round_trip_preserves_taxon_and_observation(tmp_path):
    shards = plan_shards(manifest(6), 6)
    embeddings, kept, failures = embed_shard(shards[0], fake_fetch, fake_encode, batch_size=4)
    write_shard(tmp_path, shards[0], embeddings, kept, failures)

    loaded = load_shards(tmp_path)

    assert loaded["photo_id"].tolist() == kept
    assert len(loaded) == 6
    expected = shards[0].rows.set_index("photo_id")["taxon_id"]
    assert loaded.set_index("photo_id")["taxon_id"].to_dict() == expected.to_dict()
    assert np.asarray(loaded["embedding"].iloc[0]).shape == (EMBED_DIM,)


# -- failures -------------------------------------------------------------------


def test_a_dead_photo_costs_one_photo_not_the_shard(tmp_path):
    def flaky(photo_id: int, extension: str) -> bytes:
        if photo_id in (101, 104):
            raise OSError("404 Not Found")
        return fake_fetch(photo_id, extension)

    shard = plan_shards(manifest(6), 6)[0]
    embeddings, kept, failures = embed_shard(shard, flaky, fake_encode, batch_size=2)
    write_shard(tmp_path, shard, embeddings, kept, failures)

    assert len(kept) == 4
    assert 101 not in kept and 104 not in kept
    recorded = shard_failures(tmp_path)
    assert sorted(recorded["photo_id"].tolist()) == [101, 104]
    assert "404" in recorded["reason"].iloc[0]


def test_a_shard_where_everything_fails_still_writes(tmp_path):
    def dead(photo_id: int, extension: str) -> bytes:
        raise OSError("gone")

    shard = plan_shards(manifest(4), 4)[0]
    embeddings, kept, failures = embed_shard(shard, dead, fake_encode)
    write_shard(tmp_path, shard, embeddings, kept, failures)

    assert embeddings.shape == (0, EMBED_DIM)
    assert kept == []
    assert len(shard_failures(tmp_path)) == 4
    # It must count as complete, or a permanently dead shard loops forever.
    assert pending_shards(plan_shards(manifest(4), 4), tmp_path) == []


def test_missing_photos_counts_neither_embedded_nor_failed(tmp_path):
    frame = manifest(8)
    shards = plan_shards(frame, 4)
    embeddings, kept, failures = embed_shard(shards[0], fake_fetch, fake_encode)
    write_shard(tmp_path, shards[0], embeddings, kept, failures)

    assert sorted(missing_photos(frame, tmp_path)) == [104, 105, 106, 107]


# -- vectors --------------------------------------------------------------------


def test_embeddings_are_unit_length():
    shard = plan_shards(manifest(5), 5)[0]
    embeddings, _, _ = embed_shard(shard, fake_fetch, fake_encode)

    norms = np.linalg.norm(embeddings, axis=1)
    assert np.allclose(norms, 1.0, atol=1e-5)


def test_a_zero_vector_normalises_to_zero_rather_than_nan():
    """A black image can encode to zeros. NaNs would poison the whole head."""
    out = l2_normalise(np.zeros((2, 4), dtype=np.float32))

    assert not np.isnan(out).any()
    assert out.sum() == 0.0


def test_write_shard_rejects_mismatched_lengths(tmp_path):
    shard = plan_shards(manifest(4), 4)[0]

    with pytest.raises(ValueError):
        write_shard(tmp_path, shard, np.zeros((2, EMBED_DIM)), [100])
