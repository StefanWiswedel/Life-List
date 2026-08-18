"""Tests for stage 4's metrics. No torch, no GPU, no trained model.

What is tested here is everything that can quietly lie: a temperature that does not fit,
a calibration number that says a model is honest when it is not, and a rollup score that
credits a refusal as a correct answer.
"""

from __future__ import annotations

import numpy as np
import pytest

from lifelist_train.head import (
    evaluate,
    expected_calibration_error,
    fit_temperature,
    logits_from,
    negative_log_likelihood,
    softmax,
)
from lifelist_train.taxonomy import Taxon, Taxonomy


def taxonomy() -> Taxonomy:
    """Two families under root, so a refusal is actually reachable.

    With a single family its probability is always 1.0 and the rollup can never return
    root — which is how the first version of these tests quietly could not test refusal.
    """
    return Taxonomy(
        [
            Taxon(0, None, "root", "Life"),
            Taxon(1, 0, "family", "Anatidae"),
            Taxon(2, 1, "genus", "Anas"),
            Taxon(10, 2, "species", "Anas platyrhynchos", leaf_index=0),
            Taxon(11, 2, "species", "Anas crecca", leaf_index=1),
            Taxon(3, 1, "genus", "Cygnus"),
            Taxon(12, 3, "species", "Cygnus olor", leaf_index=2),
            Taxon(4, 0, "family", "Nymphalidae"),
            Taxon(13, 4, "species", "Aglais urticae", leaf_index=3),
        ]
    )


# -- softmax and temperature ---------------------------------------------------


def test_softmax_rows_sum_to_one():
    probs = softmax(np.array([[1.0, 2.0, 3.0], [0.0, 0.0, 0.0]]))

    assert np.allclose(probs.sum(axis=1), 1.0)


def test_a_higher_temperature_flattens():
    logits = np.array([[3.0, 1.0, 0.0]])

    assert softmax(logits, 5.0).max() < softmax(logits, 1.0).max()


def test_temperature_must_be_positive():
    with pytest.raises(ValueError):
        softmax(np.array([[1.0, 2.0]]), temperature=0.0)


def test_fitting_scales_with_the_logits():
    """Scaling every logit by k must scale the fitted temperature by k.

    Asserted as a property rather than as "recovers 3.0": logits drawn from a normal are
    not calibrated at T=1 to begin with, so a test expecting an absolute answer would be
    asserting a coincidence of the sampler.
    """
    rng = np.random.default_rng(0)
    labels = rng.integers(0, 5, size=3000)
    logits = rng.normal(size=(3000, 5))
    logits[np.arange(3000), labels] += 2.0

    base = fit_temperature(logits, labels)
    scaled = fit_temperature(logits * 3.0, labels)

    assert scaled == pytest.approx(3.0 * base, rel=0.02)


def test_fitting_corrects_deliberate_overconfidence():
    rng = np.random.default_rng(3)
    labels = rng.integers(0, 5, size=3000)
    logits = rng.normal(size=(3000, 5))
    logits[np.arange(3000), labels] += 2.0

    overconfident = logits * 4.0
    fitted = fit_temperature(overconfident, labels)

    before = expected_calibration_error(
        softmax(overconfident).max(axis=1),
        softmax(overconfident).argmax(axis=1) == labels,
    )
    after = expected_calibration_error(
        softmax(overconfident, fitted).max(axis=1),
        softmax(overconfident, fitted).argmax(axis=1) == labels,
    )
    assert after < before / 2, f"{before:.3f} -> {after:.3f}"


def test_fitting_lowers_the_held_out_loss():
    rng = np.random.default_rng(1)
    labels = rng.integers(0, 4, size=1500)
    logits = rng.normal(size=(1500, 4))
    logits[np.arange(1500), labels] += 1.5
    logits *= 2.5

    fitted = fit_temperature(logits, labels)

    assert negative_log_likelihood(logits, labels, fitted) < negative_log_likelihood(
        logits, labels, 1.0
    )


def test_fitting_is_deterministic():
    rng = np.random.default_rng(2)
    labels = rng.integers(0, 3, size=500)
    logits = rng.normal(size=(500, 3))

    assert fit_temperature(logits, labels) == fit_temperature(logits, labels)


def test_no_validation_data_leaves_temperature_alone():
    assert fit_temperature(np.zeros((0, 4)), np.zeros(0, dtype=int)) == 1.0


# -- calibration ---------------------------------------------------------------


def test_a_perfectly_calibrated_set_scores_zero():
    # 100 predictions at 0.7 confidence, exactly 70 of them right.
    confidences = np.full(100, 0.7)
    correct = np.array([True] * 70 + [False] * 30)

    assert expected_calibration_error(confidences, correct) < 1e-9


def test_overconfidence_is_penalised():
    confidences = np.full(100, 0.95)
    correct = np.array([True] * 50 + [False] * 50)

    assert expected_calibration_error(confidences, correct) == pytest.approx(0.45, abs=0.01)


def test_calibration_of_nothing_is_zero_not_nan():
    assert expected_calibration_error(np.zeros(0), np.zeros(0, dtype=bool)) == 0.0


# -- the score that judges the product -----------------------------------------


def _logits_for(probabilities: list[list[float]]) -> np.ndarray:
    return np.log(np.clip(np.array(probabilities), 1e-9, None))


def test_a_confident_correct_species_scores():
    result = evaluate(taxonomy(), _logits_for([[0.90, 0.03, 0.02, 0.05]]), np.array([0]))

    assert result.rollup_accuracy == 1.0
    assert result.leaf_top1 == 1.0
    assert result.refusal_rate == 0.0


def test_a_genus_answer_counts_as_correct_for_a_species_in_it():
    # Split between the two Anas, so the rollup stops at the genus. The true leaf is one
    # of them: the answer is not wrong, it is shallower.
    result = evaluate(taxonomy(), _logits_for([[0.45, 0.44, 0.06, 0.05]]), np.array([0]))

    assert result.rollup_accuracy == 1.0
    assert result.leaf_top1 == 1.0
    assert result.mean_returned_depth == 2.0  # root -> family -> genus


def test_a_refusal_is_not_credited_as_correct():
    # Mass split across both families, so nothing clears the threshold and the rollup
    # returns root. Root contains the truth — it contains everything — but "Life" must
    # never score as an identification.
    result = evaluate(
        taxonomy(), _logits_for([[0.30, 0.20, 0.15, 0.35]]), np.array([0]), threshold=0.70
    )

    assert result.refusal_rate == 1.0
    assert result.rollup_accuracy == 0.0


def test_a_confident_wrong_genus_is_wrong():
    result = evaluate(taxonomy(), _logits_for([[0.02, 0.02, 0.92, 0.04]]), np.array([0]))

    assert result.rollup_accuracy == 0.0
    assert result.leaf_top1 == 0.0


def test_a_stricter_threshold_trades_depth_for_accuracy():
    logits = _logits_for([[0.55, 0.33, 0.07, 0.05]] * 20)
    truth = np.array([0] * 20)

    loose = evaluate(taxonomy(), logits, truth, threshold=0.50)
    strict = evaluate(taxonomy(), logits, truth, threshold=0.95)

    assert strict.mean_returned_depth < loose.mean_returned_depth


def test_logits_from_matches_a_manual_matmul():
    weights = np.array([[1.0, 0.0], [0.0, 2.0]], dtype=np.float32)
    bias = np.array([0.5, -0.5], dtype=np.float32)
    x = np.array([[3.0, 4.0]], dtype=np.float32)

    assert np.allclose(logits_from(weights, bias, x), np.array([[3.5, 7.5]]))
