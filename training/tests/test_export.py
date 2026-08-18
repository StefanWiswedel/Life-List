"""Tests for stage 6's parity checking. No torch, no onnxruntime, no exported model.

What is tested is the thing that decides whether an export shipped: the comparison. A
quantisation that quietly costs 2% looks identical to one that costs nothing unless
something measures it and has an opinion about the answer.
"""

from __future__ import annotations

import numpy as np
import pytest

from lifelist_train.export import (
    FP32_MIN_COSINE,
    INT8_MIN_COSINE,
    compare_embeddings,
    cosine_rows,
)


def test_identical_rows_are_cosine_one():
    a = np.array([[1.0, 2.0, 3.0], [0.0, 1.0, 0.0]])

    assert np.allclose(cosine_rows(a, a.copy()), 1.0)


def test_scaling_a_row_does_not_change_its_cosine():
    """Quantisation often rescales. Direction is what the head reads, not magnitude."""
    a = np.array([[1.0, 2.0, 3.0]])

    assert np.allclose(cosine_rows(a, a * 7.5), 1.0)


def test_an_opposed_row_is_minus_one():
    a = np.array([[1.0, 0.0]])

    assert cosine_rows(a, -a)[0] == pytest.approx(-1.0)


def test_a_zero_row_does_not_produce_nan():
    """A black image can encode to zeros. A NaN here would pass every > threshold check."""
    out = cosine_rows(np.zeros((1, 4)), np.ones((1, 4)))

    assert not np.isnan(out).any()


def test_shape_mismatch_is_an_error_not_a_broadcast():
    with pytest.raises(ValueError):
        cosine_rows(np.zeros((2, 4)), np.zeros((3, 4)))


def test_a_faithful_export_passes_both_gates():
    rng = np.random.default_rng(0)
    reference = rng.normal(size=(50, 16))
    exported = reference + rng.normal(scale=1e-6, size=(50, 16))

    report = compare_embeddings(reference, exported)

    assert report.ok(FP32_MIN_COSINE)
    assert report.ok(INT8_MIN_COSINE)


def test_a_badly_drifted_export_fails_the_fp32_gate():
    rng = np.random.default_rng(1)
    reference = rng.normal(size=(50, 16))
    exported = reference + rng.normal(scale=0.3, size=(50, 16))

    report = compare_embeddings(reference, exported)

    assert not report.ok(FP32_MIN_COSINE)


def test_one_bad_row_among_many_good_ones_still_fails():
    """The gate is on the minimum, not the mean. One catastrophic image is a defect."""
    rng = np.random.default_rng(2)
    reference = rng.normal(size=(200, 16))
    exported = reference.copy()
    exported[137] = -reference[137]

    report = compare_embeddings(reference, exported)

    assert report.mean_cosine > 0.98
    assert not report.ok(INT8_MIN_COSINE)


def test_top1_agreement_notices_a_class_flip():
    reference = np.array([[1.0, 0.0], [0.0, 1.0]])
    exported = np.array([[1.0, 0.0], [1.0, 0.0]])
    weight = np.eye(2)
    bias = np.zeros(2)

    report = compare_embeddings(reference, exported, weight, bias)

    assert report.top1_agreement == 0.5


def test_top1_agreement_is_nan_without_a_head():
    report = compare_embeddings(np.ones((3, 4)), np.ones((3, 4)))

    assert np.isnan(report.top1_agreement)
