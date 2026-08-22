"""Stage 4 — fit the linear head, calibrate it, and say what it is worth.

    lifelist-train --cache-dir cache --compare 50 40 30 20
    lifelist-train --cache-dir cache --min-observations 20 --commit

The head that shipped was produced by a script in a scratch directory. That made
`shared/model/head.npz` the third committed artefact in this repo with no committed builder,
after the taxonomy that shipped every vernacular null (§28) and the reference index that shipped
whichever photograph came first (§37). Both of those bugs survived for weeks because nothing
could re-run the thing that made them.

`--compare` is why this exists in this shape. Each threshold's photo set is a subset of every
lower one, so **one embedding run supports every candidate model**, and a head fit is minutes
against hours of embedding. The decision gets made on measured numbers rather than on which
threshold sounded reasonable.

**Comparing thresholds fairly is not obvious.** A 3,536-class model and a 2,294-class model are
not solving the same problem, and their overall accuracies are not comparable — the bigger one
is being asked harder questions. So every candidate is also scored on the *same* test photos:
those whose true taxon survives the strictest threshold in the comparison. That is the number
that answers "does adding thin classes damage the ones we already had", and it is the only
column in the table that can be read down the page.
"""

from __future__ import annotations

import argparse
import gc
import json
import time
from pathlib import Path
from typing import Any

import numpy as np

from ..bridge import restrict
from ..embed import load_shards
from ..evaluation import by_group, format_table
from ..gbif import build_taxonomy_nodes
from ..head import evaluate, fit_linear_head, fit_temperature, logits_from
from ..rollup import DEFAULT_THRESHOLD
from ..splits import Photo, assert_no_observation_leakage, report, split_photos
from ..taxonomy import Taxonomy
from ._common import LOG, add_common_args, cache_path, setup_logging, write_json

BACKBONE = {
    "source": "imageomics/bioclip",
    "arch": "ViT-B/16",
    "input_size": 224,
    "quantisation": "none-fp32",
    "mean": [0.48145466, 0.4578275, 0.40821073],
    "std": [0.26862954, 0.26130258, 0.27577711],
}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Train and evaluate the linear head")
    parser.add_argument("--bridge", type=Path, default=Path("shared/model/taxon_bridge.json"))
    parser.add_argument("--min-observations", type=int, default=20)
    parser.add_argument(
        "--compare",
        type=int,
        nargs="+",
        default=None,
        help="fit a head at each of these thresholds and print one table. Nothing is written.",
    )
    parser.add_argument("--out", type=Path, default=Path("shared/model"))
    parser.add_argument("--model-version", default=None, help="defaults to today's date")
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--batch-size", type=int, default=2048)
    parser.add_argument("--learning-rate", type=float, default=3e-3)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument(
        "--commit",
        action="store_true",
        help="write head.npz, taxonomy.json and model_meta.json. Without it, nothing is saved.",
    )
    return add_common_args(parser)


def observation_counts(embedded: Any) -> dict[int, int]:
    """Distinct observations per iNaturalist taxon, from the embedded rows themselves.

    Exact for any threshold below the photo cap. `sample_photos` takes one photograph from
    every observation before it takes a second from any, so a taxon with 40 observations
    contributes 40 distinct ones to a manifest capped at 150 — the cap only bites above itself.
    """
    return embedded.groupby("taxon_id")["observation_uuid"].nunique().to_dict()


def taxa_above(counts: dict[int, int], minimum: int) -> list[int]:
    return sorted(int(t) for t, n in counts.items() if n >= minimum)


def prepare(
    embedded: Any,
    bridge: dict[str, Any],
    keep_inat: list[int],
) -> tuple[Taxonomy, Any]:
    """Taxonomy for this threshold, plus the rows that survive it with their leaf indices."""
    mapping, parents, taxa = restrict(bridge, keep_inat)
    taxonomy = Taxonomy(build_taxonomy_nodes(taxa, indeterminate_parents=parents))

    leaf_of = {
        node.taxon_id: node.leaf_index
        for node in taxonomy.nodes.values()
        if node.leaf_index is not None
    }
    rows = embedded[embedded["taxon_id"].astype(int).isin(mapping)].copy()
    rows["gbif"] = rows["taxon_id"].astype(int).map(mapping)
    rows["leaf"] = rows["gbif"].map(leaf_of)
    rows = rows[rows["leaf"].notna()].copy()
    rows["leaf"] = rows["leaf"].astype(int)
    return taxonomy, rows


def fit(taxonomy: Taxonomy, rows: Any, args: argparse.Namespace) -> dict[str, Any]:
    """One candidate model: split, fit, calibrate, and hold on to the test predictions."""
    observation_index = {u: i for i, u in enumerate(rows["observation_uuid"].unique())}
    photos = [
        Photo(int(p), observation_index[o], int(leaf))
        for p, o, leaf in zip(
            rows["photo_id"], rows["observation_uuid"], rows["leaf"], strict=True
        )
    ]
    splits = split_photos(photos, seed=args.seed)
    assert_no_observation_leakage(splits)
    summary = report(splits)

    position = {int(p): i for i, p in enumerate(rows["photo_id"])}
    embeddings = rows["embedding"].to_numpy()
    labels = rows["leaf"].to_numpy()

    def take(name: str) -> tuple[np.ndarray, np.ndarray]:
        # Stacked per split rather than once for everything: a 400,000 x 512 float32 array is
        # 820 MB, and materialising it only to slice three disjoint pieces out of it means
        # paying for the whole thing twice at the moment it matters most.
        index = np.array([position[p.photo_id] for p in splits[name]], dtype=np.int64)
        return np.stack(embeddings[index]).astype(np.float32), labels[index]

    x_train, y_train = take("train")
    x_val, y_val = take("val")
    x_test, y_test = take("test")

    started = time.time()
    weight, bias = fit_linear_head(
        x_train,
        y_train,
        n_classes=taxonomy.n_taxa,
        epochs=args.epochs,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
    )
    elapsed = time.time() - started

    temperature = fit_temperature(logits_from(weight, bias, x_val), y_val)

    return {
        "taxonomy": taxonomy,
        "weight": weight,
        "bias": bias,
        "temperature": float(temperature),
        "test_logits": logits_from(weight, bias, x_test),
        "test_labels": y_test,
        "counts": summary.counts,
        "missing_from_train": len(summary.taxa_missing_from_train),
        "seconds": elapsed,
        "photos": int(len(rows)),
    }


def common_mask(taxonomy: Taxonomy, labels: np.ndarray, common_gbif: set[int]) -> np.ndarray:
    """Test photos whose true taxon is in every candidate's vocabulary.

    The only column that can honestly be read down the page: each model is answering the same
    questions about the same organisms, and the extra classes are just extra ways to be wrong.
    """
    return np.array([taxonomy.leaf_id(int(i)) in common_gbif for i in labels], dtype=bool)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    if not args.bridge.exists():
        LOG.error("%s not found — run lifelist-bridge first", args.bridge)
        return 1
    bridge = json.loads(args.bridge.read_text(encoding="utf-8"))

    shards = cache_path(args.cache_dir, "embeddings")
    started = time.time()
    embedded = load_shards(shards)
    if embedded.empty:
        LOG.error("no embeddings in %s — run lifelist-embed first", shards)
        return 1
    LOG.info(
        "%d embeddings over %d iNaturalist taxa (%.0f s)",
        len(embedded), embedded["taxon_id"].nunique(), time.time() - started,
    )

    counts = observation_counts(embedded)
    thresholds = sorted(args.compare, reverse=True) if args.compare else [args.min_observations]

    # The strictest threshold defines the shared vocabulary every candidate is judged on.
    strictest = max(thresholds)
    strict_taxonomy, _ = prepare(embedded, bridge, taxa_above(counts, strictest))
    common_gbif = {
        node.taxon_id for node in strict_taxonomy.nodes.values() if node.leaf_index is not None
    }
    LOG.info("shared vocabulary for the comparison: %d taxa (>=%d)", len(common_gbif), strictest)

    results: list[tuple[int, dict[str, Any]]] = []
    for minimum in thresholds:
        keep = taxa_above(counts, minimum)
        taxonomy, rows = prepare(embedded, bridge, keep)
        LOG.info(
            ">=%d observations: %d iNaturalist taxa -> %d leaves, %d photos",
            minimum, len(keep), taxonomy.n_taxa, len(rows),
        )
        outcome = fit(taxonomy, rows, args)

        outcome["overall"] = evaluate(
            taxonomy, outcome["test_logits"], outcome["test_labels"],
            temperature=outcome["temperature"], threshold=args.threshold,
        )
        mask = common_mask(taxonomy, outcome["test_labels"], common_gbif)
        outcome["shared"] = evaluate(
            taxonomy, outcome["test_logits"][mask], outcome["test_labels"][mask],
            temperature=outcome["temperature"], threshold=args.threshold,
        )
        # Rendered now, while the logits are still here, because they are about to go.
        outcome["group_table"] = format_table(
            by_group(
                taxonomy, outcome["test_logits"], outcome["test_labels"],
                temperature=outcome["temperature"], threshold=args.threshold,
            )
        )
        LOG.info("  own test set : %s", outcome["overall"].summary())
        LOG.info("  shared taxa  : %s", outcome["shared"].summary())

        # A candidate's test logits are photos x taxa: half a gigabyte at 3,536 classes, and
        # `--compare` holds every candidate at once. Keeping four of them alongside a 410,000
        # row embedding frame is what an out-of-memory kill looks like three fits in, with
        # nothing in the log to say why. Only the model actually being written needs its arrays.
        outcome["test_logits"] = None
        outcome["test_labels"] = outcome["test_labels"][:0]
        if args.compare:
            outcome["weight"] = outcome["bias"] = None
        del rows, mask
        gc.collect()
        results.append((minimum, outcome))

    print()
    print("Candidate models. 'shared' is the same test photos for every row — the extra classes")
    print("are only extra ways to be wrong — so it is the only column to read down the page.")
    print()
    header = (
        f"{'min obs':>8}{'taxa':>7}{'photos':>10}{'T':>7}"
        f"{'own rollup':>12}{'own top-1':>11}{'shared rollup':>15}{'shared top-1':>14}{'ECE':>7}"
    )
    print(header)
    print("-" * len(header))
    for minimum, outcome in results:
        own, shared = outcome["overall"], outcome["shared"]
        print(
            f"{minimum:>8}{outcome['taxonomy'].n_taxa:>7}{outcome['photos']:>10,}"
            f"{outcome['temperature']:>7.3f}{own.rollup_accuracy * 100:>11.1f}%"
            f"{own.leaf_top1 * 100:>10.1f}%{shared.rollup_accuracy * 100:>14.1f}%"
            f"{shared.leaf_top1 * 100:>13.1f}%{own.expected_calibration_error:>7.3f}"
        )
    print()

    for minimum, outcome in results:
        print(f"By group at >={minimum} observations (own test set, calibrated):")
        print(outcome["group_table"])
        print()

    if not args.commit:
        print("Nothing written. Re-run with --min-observations N --commit to save one.")
        return 0

    if args.compare:
        LOG.error("--compare fits several models; pass --min-observations N --commit to save one")
        return 1

    minimum, outcome = results[0]
    taxonomy = outcome["taxonomy"]
    version = args.model_version or time.strftime("%Y-%m-%d")
    args.out.mkdir(parents=True, exist_ok=True)

    np.savez_compressed(args.out / "head.npz", weight=outcome["weight"], bias=outcome["bias"])
    write_json(
        args.out / "taxonomy.json",
        [
            {
                "taxon_id": node.taxon_id,
                "parent_id": node.parent_id,
                "rank": node.rank,
                "scientific_name": node.scientific_name,
                "vernacular_da": node.vernacular_da,
                "vernacular_en": node.vernacular_en,
                "leaf_index": node.leaf_index,
            }
            for node in taxonomy.nodes.values()
        ],
    )
    write_json(
        args.out / "model_meta.json",
        {
            "spec_version": 1,
            "model_version": version,
            "embedding_dim": int(outcome["weight"].shape[1]),
            "n_taxa": int(taxonomy.n_taxa),
            "temperature": outcome["temperature"],
            "fusion_mode": "embedding",
            "default_threshold": args.threshold,
            "backbone": BACKBONE,
            "trained_at": version,
            "max_images": 5,
            "min_observations": minimum,
            "trained_on": {
                "photos": outcome["photos"],
                "test": int(outcome["overall"].n),
            },
        },
    )
    named = sum(1 for n in taxonomy.nodes.values() if n.vernacular_en)
    LOG.info("wrote head.npz, taxonomy.json and model_meta.json to %s", args.out)
    # §28 shipped this field null on all 4,657 nodes and nothing noticed for weeks.
    LOG.info("vernaculars present on %d of %d nodes", named, len(taxonomy.nodes))
    if named == 0:
        LOG.error("every vernacular is null — this is the §28 bug, do not ship this taxonomy")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
