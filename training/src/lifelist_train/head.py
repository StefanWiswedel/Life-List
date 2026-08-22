"""Stage 4 — the classification head, and the numbers that judge it.

Everything that decides anything is numpy over already-computed arrays, so temperature
fitting, calibration error and rollup scoring are testable without torch, a GPU, or a
trained model. Only `fit_linear_head` needs torch, and it is the least interesting part:
a single linear layer over frozen embeddings.

The metric that matters is **rollup accuracy**, not leaf top-1. Leaf top-1 is a diagnostic.
An app whose argument is "answer at the deepest rank you can defend" is judged on whether
the rank it returns is right, and on whether the confidence it prints means what it says —
so calibration error sits beside accuracy rather than in a footnote (VERIFICATION.md §18).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .rollup import DEFAULT_THRESHOLD, rollup
from .taxonomy import Taxonomy


@dataclass(frozen=True, slots=True)
class Evaluation:
    """What a run is allowed to claim."""

    n: int
    leaf_top1: float
    rollup_accuracy: float
    refusal_rate: float
    mean_returned_depth: float
    expected_calibration_error: float
    temperature: float

    def summary(self) -> str:
        return (
            f"n={self.n}  rollup={self.rollup_accuracy:.1%}  leaf top-1={self.leaf_top1:.1%}  "
            f"refused={self.refusal_rate:.1%}  mean depth={self.mean_returned_depth:.2f}  "
            f"ECE={self.expected_calibration_error:.3f}  T={self.temperature:.3f}"
        )


def softmax(logits: np.ndarray, temperature: float = 1.0) -> np.ndarray:
    """Row-wise softmax with temperature. Spec §2: applied exactly once, before rollup."""
    if temperature <= 0:
        raise ValueError(f"temperature must be positive, got {temperature}")
    scaled = np.asarray(logits, dtype=np.float64) / temperature
    scaled -= scaled.max(axis=1, keepdims=True)
    exp = np.exp(scaled)
    return exp / exp.sum(axis=1, keepdims=True)


def negative_log_likelihood(logits: np.ndarray, labels: np.ndarray, temperature: float) -> float:
    probs = softmax(logits, temperature)
    picked = probs[np.arange(len(labels)), labels]
    return float(-np.log(np.clip(picked, 1e-12, None)).mean())


def fit_temperature(
    logits: np.ndarray,
    labels: np.ndarray,
    lo: float = 0.05,
    hi: float = 10.0,
    iterations: int = 60,
) -> float:
    """Fit a single scalar temperature on held-out logits by golden-section search.

    NLL in temperature is unimodal for a fixed logit matrix, so a bracketed 1-D search is
    both sufficient and deterministic — no learning rate, no seed, no early stopping, and
    the same answer on every machine. Gradient descent here would be three hyperparameters
    in exchange for nothing.
    """
    if len(logits) == 0:
        return 1.0

    invphi = (np.sqrt(5.0) - 1.0) / 2.0
    a, b = lo, hi
    c, d = b - invphi * (b - a), a + invphi * (b - a)
    fc, fd = (
        negative_log_likelihood(logits, labels, c),
        negative_log_likelihood(logits, labels, d),
    )
    for _ in range(iterations):
        if fc < fd:
            b, d, fd = d, c, fc
            c = b - invphi * (b - a)
            fc = negative_log_likelihood(logits, labels, c)
        else:
            a, c, fc = c, d, fd
            d = a + invphi * (b - a)
            fd = negative_log_likelihood(logits, labels, d)
    return float((a + b) / 2.0)


def expected_calibration_error(
    confidences: np.ndarray, correct: np.ndarray, bins: int = 15
) -> float:
    """Mean gap between stated confidence and observed accuracy, weighted by bin size.

    This is the number that says whether "88%" means anything. Since §18 established that
    coarse fallback is not unique to us but stated confidence is, this is arguably the
    headline metric of the whole project.
    """
    confidences = np.asarray(confidences, dtype=np.float64)
    correct = np.asarray(correct, dtype=bool)
    if len(confidences) == 0:
        return 0.0

    edges = np.linspace(0.0, 1.0, bins + 1)
    total = 0.0
    for lo, hi in zip(edges[:-1], edges[1:], strict=True):
        mask = (confidences > lo) & (confidences <= hi) if lo > 0 else (confidences <= hi)
        if not mask.any():
            continue
        total += mask.mean() * abs(correct[mask].mean() - confidences[mask].mean())
    return float(total)


EVAL_BLOCK = 4096


def evaluate(
    taxonomy: Taxonomy,
    logits: np.ndarray,
    true_leaf_indices: np.ndarray,
    temperature: float = 1.0,
    threshold: float = DEFAULT_THRESHOLD,
) -> Evaluation:
    """Score a set of predictions the way the product is judged.

    `mean_returned_depth` is the depth of the node actually returned. It is reported next
    to accuracy because the two trade against each other: an app that always answers
    "Life" is perfectly accurate and useless, and one that always guesses a species is
    Arter. Neither number alone can catch that.
    """
    correct: list[bool] = []
    depths: list[int] = []
    confidences: list[float] = []
    refusals = 0
    hits = 0

    # In blocks, because `softmax` works in float64 and holds three arrays at once: at 38,000
    # test photographs and 3,482 classes that is three gigabytes of peak for a number that is
    # then thrown away row by row. The softmax is row-wise, so blocking changes the arithmetic
    # not at all — only how much of it exists at any moment.
    for start in range(0, len(true_leaf_indices), EVAL_BLOCK):
        block = softmax(logits[start : start + EVAL_BLOCK], temperature)
        truth = true_leaf_indices[start : start + EVAL_BLOCK]
        hits += int((block.argmax(axis=1) == truth).sum())

        for row, true_index in zip(block, truth, strict=True):
            result = rollup(taxonomy, row.astype(np.float32), threshold=threshold)
            true_leaf_id = taxonomy.leaf_id(int(true_index))
            hit = taxonomy.is_ancestor_or_self(result.taxon_id, true_leaf_id)
            if result.taxon_id == taxonomy.root_id:
                refusals += 1
                hit = False  # a refusal is honest, but it is not a correct answer
            correct.append(bool(hit))
            depths.append(len(taxonomy.lineage(result.taxon_id)) - 1)
            confidences.append(float(result.probability))

    leaf_top1 = hits / len(true_leaf_indices)

    return Evaluation(
        n=len(true_leaf_indices),
        leaf_top1=leaf_top1,
        rollup_accuracy=float(np.mean(correct)),
        refusal_rate=refusals / len(true_leaf_indices),
        mean_returned_depth=float(np.mean(depths)),
        expected_calibration_error=expected_calibration_error(
            np.asarray(confidences), np.asarray(correct)
        ),
        temperature=temperature,
    )


def fit_linear_head(
    train_x: np.ndarray,
    train_y: np.ndarray,
    n_classes: int,
    epochs: int = 30,
    batch_size: int = 1024,
    learning_rate: float = 1e-3,
    weight_decay: float = 1e-4,
    seed: int = 0,
) -> tuple[np.ndarray, np.ndarray]:
    """Train a single linear layer over frozen embeddings. Returns (weight, bias).

    Deliberately a linear probe: the backbone is frozen, so anything deeper is fitting
    noise in 512 dimensions. Returns plain arrays so everything downstream — evaluation,
    calibration, export — stays numpy and stays testable.
    """
    import torch

    torch.manual_seed(seed)
    x = torch.from_numpy(np.asarray(train_x, dtype=np.float32))
    y = torch.from_numpy(np.asarray(train_y, dtype=np.int64))

    model = torch.nn.Linear(x.shape[1], n_classes)
    optimiser = torch.optim.AdamW(model.parameters(), lr=learning_rate, weight_decay=weight_decay)
    loss_fn = torch.nn.CrossEntropyLoss()
    generator = torch.Generator().manual_seed(seed)

    for _ in range(epochs):
        order = torch.randperm(len(x), generator=generator)
        for start in range(0, len(x), batch_size):
            batch = order[start : start + batch_size]
            optimiser.zero_grad()
            loss_fn(model(x[batch]), y[batch]).backward()
            optimiser.step()

    return (
        model.weight.detach().numpy().astype(np.float32),
        model.bias.detach().numpy().astype(np.float32),
    )


def logits_from(weights: np.ndarray, bias: np.ndarray, x: np.ndarray) -> np.ndarray:
    return np.asarray(x, dtype=np.float32) @ np.asarray(weights, dtype=np.float32).T + bias
