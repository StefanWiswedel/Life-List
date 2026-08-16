"""Tests for fusion and calibration — shared/taxonomy-spec.md §2, §3."""

from __future__ import annotations

import numpy as np
import pytest

from lifelist_train.fusion import (
    expected_calibration_error,
    fit_temperature,
    fuse_embeddings,
    fuse_probabilities,
    l2_normalize,
    softmax,
)


def test_fused_embedding_is_unit_length():
    """The renormalisation in spec §3.1 is required, not cosmetic."""
    rng = np.random.default_rng(0)
    e = rng.normal(size=(5, 512))
    fused = fuse_embeddings(e)

    assert np.linalg.norm(fused) == pytest.approx(1.0)


def test_single_embedding_fuses_to_itself():
    rng = np.random.default_rng(1)
    e = rng.normal(size=(1, 512))
    assert np.allclose(fuse_embeddings(e), l2_normalize(e)[0])


def test_fusion_ignores_input_magnitude():
    """Only direction should matter — an over-exposed photo must not get more vote."""
    rng = np.random.default_rng(2)
    e = rng.normal(size=(3, 512))
    scaled = e * np.array([[0.01], [1.0], [100.0]])

    assert np.allclose(fuse_embeddings(e), fuse_embeddings(scaled))


def test_fusion_is_order_invariant():
    rng = np.random.default_rng(3)
    e = rng.normal(size=(4, 512))

    assert np.allclose(fuse_embeddings(e), fuse_embeddings(e[::-1]), atol=1e-12)


def test_duplicate_images_do_not_change_the_answer():
    rng = np.random.default_rng(4)
    e = rng.normal(size=(1, 512))

    assert np.allclose(fuse_embeddings(e), fuse_embeddings(np.repeat(e, 5, axis=0)))


def test_fusing_agreeing_embeddings_sharpens_confidence():
    """Adding photos of the same individual must raise *mean* confidence (spec §3.3).

    Deliberately averaged over many observations rather than asserted per-draw: a
    single unlucky second photo can lower confidence, and a test that forbids that
    would be testing noise. The spec's claim — and the app's central pleasure — is
    about the trend across the validation set.
    """
    rng = np.random.default_rng(5)
    head = rng.normal(size=(512, 20)) * 0.5
    ks = (1, 2, 3, 5)
    totals = dict.fromkeys(ks, 0.0)

    for _ in range(200):
        truth = l2_normalize(rng.normal(size=512))
        head[:, 0] = truth * 8.0  # class 0 is the correct taxon for this observation
        noisy = l2_normalize(truth + rng.normal(scale=0.6, size=(5, 512)))
        for k in ks:
            totals[k] += softmax(fuse_embeddings(noisy[:k]) @ head)[0]

    means = [totals[k] / 200 for k in ks]

    assert means == sorted(means), f"mean confidence must not fall with k: {means}"
    assert means[-1] > means[0] * 1.2, f"5 photos should beat 1 clearly: {means}"


# -- probability-space fusion ---------------------------------------------------


def test_probability_fusion_returns_a_distribution():
    rng = np.random.default_rng(6)
    p = rng.dirichlet(np.ones(50), size=4)
    fused = fuse_probabilities(p)

    assert fused.sum() == pytest.approx(1.0)
    assert np.all(fused >= 0)


def test_probability_fusion_handles_zeros_without_nan():
    """Clamping before the log (spec §3.2) — a confident image can zero out a class."""
    p = np.array([[1.0, 0.0, 0.0], [0.2, 0.5, 0.3]])
    fused = fuse_probabilities(p)

    assert np.all(np.isfinite(fused))
    assert fused.sum() == pytest.approx(1.0)


def test_probability_fusion_is_order_invariant():
    rng = np.random.default_rng(7)
    p = rng.dirichlet(np.ones(30), size=3)

    assert np.allclose(fuse_probabilities(p), fuse_probabilities(p[::-1]))


def test_geometric_mean_penalises_disagreement_more_than_arithmetic():
    """The reason to offer probability fusion at all: one confident 'no' should count."""
    p = np.array([[0.9, 0.05, 0.05], [0.01, 0.9, 0.09]])
    geo = fuse_probabilities(p)
    arith = p.mean(axis=0)

    assert geo[0] < arith[0]


# -- temperature ----------------------------------------------------------------


def test_softmax_temperature_softens_and_sharpens():
    z = np.array([3.0, 1.0, 0.5])

    assert softmax(z, 5.0).max() < softmax(z, 1.0).max()
    assert softmax(z, 0.5).max() > softmax(z, 1.0).max()


def test_softmax_rejects_non_positive_temperature():
    with pytest.raises(ValueError, match="must be positive"):
        softmax(np.array([1.0, 2.0]), temperature=0.0)


def test_softmax_is_numerically_stable_for_large_logits():
    z = np.array([1000.0, 999.0, 1.0])
    p = softmax(z)

    assert np.all(np.isfinite(p))
    assert p.sum() == pytest.approx(1.0)


def test_fit_temperature_recovers_a_known_overconfidence():
    """Overconfident logits should be fitted with T > 1."""
    rng = np.random.default_rng(8)
    n, k = 4000, 20
    true_logits = rng.normal(size=(n, k))
    labels = np.array([rng.choice(k, p=softmax(row)) for row in true_logits])
    overconfident = true_logits * 2.5  # the model is sharper than reality

    t = fit_temperature(overconfident, labels)

    assert t == pytest.approx(2.5, rel=0.15)


def test_temperature_scaling_improves_calibration_error():
    rng = np.random.default_rng(9)
    n, k = 4000, 20
    true_logits = rng.normal(size=(n, k))
    labels = np.array([rng.choice(k, p=softmax(row)) for row in true_logits])
    overconfident = true_logits * 2.5

    before = expected_calibration_error(softmax(overconfident), labels)
    t = fit_temperature(overconfident, labels)
    after = expected_calibration_error(softmax(overconfident, t), labels)

    assert after < before
    assert after < 0.05


def test_temperature_does_not_change_top_1():
    """Calibration changes the number shown, never the identification."""
    rng = np.random.default_rng(10)
    z = rng.normal(size=(200, 30))

    assert np.array_equal(softmax(z, 1.0).argmax(1), softmax(z, 3.7).argmax(1))
