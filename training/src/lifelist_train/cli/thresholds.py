"""Fit one threshold per group, against an accuracy a person can actually ask for.

    lifelist-thresholds --cache-dir cache
    lifelist-thresholds --cache-dir cache --commit

No training. The head is already fitted and committed; this splits the embeddings the same way
stage 4 did, predicts with the head as it stands, and sweeps the threshold. Minutes, not hours,
which is what makes it worth doing at all.

It prints the table first and writes nothing without `--commit`, because the table is the point:
the interesting question is not "what threshold" but "what does a group cost to be sure about",
and that is a thing to look at before it becomes a default.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from ..bridge import restrict
from ..embed import load_shards
from ..gbif import build_taxonomy_nodes
from ..head import logits_from
from ..splits import Photo, assert_no_observation_leakage, split_photos
from ..taxonomy import Taxonomy
from ..thresholds import TARGETS, document, format_table, sweep, table
from ._common import LOG, add_common_args, cache_path, setup_logging, shared_model


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Fit a rollup threshold per group")
    parser.add_argument("--bridge", type=Path, default=shared_model("taxon_bridge.json"))
    parser.add_argument("--head", type=Path, default=shared_model("head.npz"))
    parser.add_argument("--meta", type=Path, default=shared_model("model_meta.json"))
    parser.add_argument("--min-observations", type=int, default=20)
    parser.add_argument("--seed", type=int, default=0, help="must match the seed stage 4 used")
    parser.add_argument("--minimum", type=int, default=100, help="test photos a group must have")
    parser.add_argument(
        "--targets", type=float, nargs="+", default=list(TARGETS),
        help="accuracies a person might ask for",
    )
    parser.add_argument(
        "--commit", action="store_true",
        help="write group_thresholds into model_meta.json. Without it, nothing is saved.",
    )
    return add_common_args(parser)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    for path in (args.bridge, args.head, args.meta):
        if not path.exists():
            LOG.error("%s not found", path)
            return 1

    bridge = json.loads(args.bridge.read_text(encoding="utf-8"))
    meta = json.loads(args.meta.read_text(encoding="utf-8"))
    head = np.load(args.head)
    weight, bias = head["weight"], head["bias"]

    embedded = load_shards(cache_path(args.cache_dir, "embeddings"))
    if embedded.empty:
        LOG.error("no embeddings — this needs the same cache stage 4 was fitted on")
        return 1

    counts = embedded.groupby("taxon_id")["observation_uuid"].nunique().to_dict()
    keep = sorted(int(t) for t, n in counts.items() if n >= args.min_observations)
    mapping, parents, taxa = restrict(bridge, keep)
    taxonomy = Taxonomy(build_taxonomy_nodes(taxa, indeterminate_parents=parents))
    if taxonomy.n_taxa != weight.shape[0]:
        LOG.error(
            "the taxonomy has %d leaves and the head has %d classes — they are not the same "
            "model, and a threshold fitted across them would be meaningless",
            taxonomy.n_taxa, weight.shape[0],
        )
        return 1

    leaf_of = {
        node.taxon_id: node.leaf_index
        for node in taxonomy.nodes.values()
        if node.leaf_index is not None
    }
    rows = embedded[embedded["taxon_id"].astype(int).isin(mapping)].copy()
    rows["leaf"] = rows["taxon_id"].astype(int).map(mapping).map(leaf_of)
    rows = rows[rows["leaf"].notna()].copy()
    rows["leaf"] = rows["leaf"].astype(int)

    # The same split, from the same seed: a threshold fitted on photographs the head trained on
    # would be measuring how well it memorised them.
    observation_index = {u: i for i, u in enumerate(rows["observation_uuid"].unique())}
    photos = [
        Photo(int(p), observation_index[o], int(leaf))
        for p, o, leaf in zip(rows["photo_id"], rows["observation_uuid"], rows["leaf"], strict=True)
    ]
    splits = split_photos(photos, seed=args.seed)
    assert_no_observation_leakage(splits)

    position = {int(p): i for i, p in enumerate(rows["photo_id"])}
    index = np.array([position[p.photo_id] for p in splits["test"]], dtype=np.int64)
    features = np.stack(rows["embedding"].to_numpy()[index]).astype(np.float32)
    labels = rows["leaf"].to_numpy()[index]
    LOG.info("%d test photographs over %d leaves", len(labels), taxonomy.n_taxa)

    logits = logits_from(weight, bias, features)
    swept = sweep(taxonomy, logits, labels, temperature=float(meta["temperature"]))
    chosen = table(swept, targets=tuple(args.targets), minimum=args.minimum)

    print()
    print("What each group costs to be sure about. The threshold is the *lowest* that reaches")
    print("the target, because every higher one buys the same accuracy with a shallower answer.")
    print()
    print(format_table(chosen))
    print()
    small = [g for g, points in swept.items() if points and points[0].n < args.minimum]
    if small:
        LOG.info("left out for want of evidence (<%d test photos): %s", args.minimum, small)

    if not args.commit:
        print("Nothing written. Re-run with --commit to save these into model_meta.json.")
        return 0

    meta["group_thresholds"] = document(chosen)
    meta["group_threshold_targets"] = [float(t) for t in args.targets]
    args.meta.write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    LOG.info("wrote group_thresholds for %d targets into %s", len(args.targets), args.meta)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
