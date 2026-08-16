"""Multi-image fusion and temperature calibration — shared/taxonomy-spec.md §2, §3."""

from __future__ import annotations

from typing import Literal

import numpy as np

FusionMode = Literal["embedding", "probability"]

_EPS = 1e-12


def l2_normalize(e: np.ndarray, axis: int = -1) -> np.ndarray:
    norm = np.linalg.norm(e, axis=axis, keepdims=True)
    if np.any(norm < _EPS):
        raise ValueError("cannot L2-normalise a zero-length embedding")
    return e / norm


def fuse_embeddings(embeddings: np.ndarray) -> np.ndarray:
    """Spec §3.1 — normalise, average, renormalise.

    ``embeddings`` is (k, dim). The renormalisation is required: the head is trained
    on unit vectors and the mean of unit vectors is not one.
    """
    e = np.atleast_2d(np.asarray(embeddings, dtype=np.float64))
    if e.shape[0] < 1:
        raise ValueError("need at least one embedding")
    return l2_normalize(l2_normalize(e).mean(axis=0))


def softmax(z: np.ndarray, temperature: float = 1.0) -> np.ndarray:
    """Temperature-scaled softmax (spec §2). Applied exactly once per inference."""
    if temperature <= 0:
        raise ValueError(f"temperature must be positive, got {temperature}")
    scaled = np.asarray(z, dtype=np.float64) / temperature
    scaled = scaled - scaled.max(axis=-1, keepdims=True)
    exp = np.exp(scaled)
    return exp / exp.sum(axis=-1, keepdims=True)


def fuse_probabilities(probs: np.ndarray) -> np.ndarray:
    """Spec §3.2 — geometric mean in log space, renormalised.

    ``probs`` is (k, n_taxa), each row already temperature-scaled.
    """
    p = np.atleast_2d(np.asarray(probs, dtype=np.float64))
    if p.shape[0] < 1:
        raise ValueError("need at least one probability vector")
    log_p = np.log(np.clip(p, _EPS, None)).mean(axis=0)
    return softmax(log_p)


def fit_temperature(
    logits: np.ndarray,
    labels: np.ndarray,
    max_iter: int = 200,
) -> float:
    """Fit a single scalar temperature by minimising NLL on a held-out split.

    Raw softmax is overconfident; this is what makes the displayed percentage mean
    something (build prompt §2.4). Golden-section search over log T — one parameter,
    a smooth convex-in-practice objective, and no autograd dependency in the
    training package.
    """
    z = np.asarray(logits, dtype=np.float64)
    y = np.asarray(labels, dtype=np.int64)
    if z.ndim != 2:
        raise ValueError(f"expected (n, n_taxa) logits, got shape {z.shape}")
    if len(y) != len(z):
        raise ValueError("logits and labels have different lengths")

    def nll(log_t: float) -> float:
        p = softmax(z, temperature=float(np.exp(log_t)))
        return float(-np.log(np.clip(p[np.arange(len(y)), y], _EPS, None)).mean())

    lo, hi = np.log(0.05), np.log(20.0)
    invphi = (np.sqrt(5.0) - 1.0) / 2.0
    c, d = hi - invphi * (hi - lo), lo + invphi * (hi - lo)
    fc, fd = nll(c), nll(d)
    for _ in range(max_iter):
        if abs(hi - lo) < 1e-6:
            break
        if fc < fd:
            hi, d, fd = d, c, fc
            c = hi - invphi * (hi - lo)
            fc = nll(c)
        else:
            lo, c, fc = c, d, fd
            d = lo + invphi * (hi - lo)
            fd = nll(d)
    return float(np.exp((lo + hi) / 2.0))


def expected_calibration_error(probs: np.ndarray, labels: np.ndarray, n_bins: int = 15) -> float:
    """ECE — the number behind the reliability diagram in build prompt §5."""
    p = np.asarray(probs, dtype=np.float64)
    y = np.asarray(labels, dtype=np.int64)
    conf = p.max(axis=1)
    pred = p.argmax(axis=1)
    correct = (pred == y).astype(np.float64)

    edges = np.linspace(0.0, 1.0, n_bins + 1)
    ece = 0.0
    for lo, hi in zip(edges[:-1], edges[1:], strict=True):
        mask = (conf > lo) & (conf <= hi)
        if not mask.any():
            continue
        ece += mask.mean() * abs(correct[mask].mean() - conf[mask].mean())
    return float(ece)
