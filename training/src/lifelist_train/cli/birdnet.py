"""Build the audio taxonomy and the BirdNET class map — build plan §3.5.

    lifelist-birdnet --labels <labels.txt> -v
    lifelist-birdnet --labels <labels.txt> --commit

Two artefacts and a report:

* `shared/model/audio_taxonomy.json` — a second tree, whose leaves are the species BirdNET can
  hear. Not the vision taxonomy: that file is the image head's output space, its leaf indices
  are contiguous into that head, and 534 of BirdNET's western-palearctic classes have no place
  in it (VERIFICATION §61). Records store a GBIF taxon id, which both trees share, so the life
  list stays one list.
* `shared/model/birdnet_classes.json` — output vector position → GBIF taxon key. **Index order
  is the contract.** A label file that gains or loses a class shifts every index after it and
  would misattribute every detection past that point, which is why BUILD.md §3.1 says the
  bridge is re-run on every version bump and its report is read rather than skimmed.
* The refusals, grouped by what a person would do about each. A bridge that drops classes
  quietly looks exactly like a bridge that works.

Nothing is written without `--commit`.
"""

from __future__ import annotations

import argparse
import json
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from typing import Any

from ..birdnet import parse_labels
from ..birdnet_taxonomy import class_map, report_lines, resolve, vernaculars
from ..gbif import GbifClient, build_taxonomy_nodes
from ..taxonomy import Taxonomy
from ._common import LOG, setup_logging, shared_model

#: Curated BirdNET→GBIF name decisions, so a resolved disagreement stays resolved.
ALIASES = Path(__file__).resolve().parents[4] / "shared" / "birdnet_aliases.json"


def load_aliases(path: Path) -> dict[str, str]:
    """Missing is fine — an empty alias file and no alias file mean the same thing."""
    if not path.exists():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    return {str(k): str(v) for k, v in (raw.get("aliases") or {}).items()}


def matcher(client: GbifClient, workers: int) -> Any:
    """Resolve names concurrently, but hand `resolve` a plain callable.

    `resolve` stays a pure function over an injected matcher — it must not know that anything
    is threaded — so the concurrency lives here: every distinct name is fetched up front and
    the callable then reads from the result.
    """

    def prefetch(names: list[str]) -> dict[str, Any]:
        out: dict[str, Any] = {}
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for name, payload in zip(
                names, pool.map(lambda n: _safe_match(client, n), names), strict=True
            ):
                out[name] = payload
        return out

    return prefetch


ATTEMPTS = 4


def _safe_match(client: GbifClient, name: str, attempts: int = ATTEMPTS) -> Any:
    """Retry a name before believing GBIF has never heard of it.

    A dropped connection is not a taxonomic fact. Without this, three connection resets in a
    801-name run put the goshawk, the Levant sparrowhawk and the bank myna in the artefact as
    "no match at all" — indistinguishable, once committed, from a name GBIF really lacks. The
    report is meant to be read and acted on, so it must not be mostly noise.
    """
    for attempt in range(attempts):
        try:
            return client.match(name)
        except Exception as exc:  # noqa: BLE001 — one bad name must not end the run
            if attempt + 1 == attempts:
                LOG.warning("match failed for %r after %d attempts: %s", name, attempts, exc)
                return None
            time.sleep(0.5 * 2**attempt)
    return None


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--labels", required=True, type=Path, help="BirdNET labels file, one per line")
    ap.add_argument("--region", default="western-palearctic", help="recorded in the artefact")
    ap.add_argument("--version", default="3.0-preview3.1", help="pinned BirdNET release")
    ap.add_argument("--aliases", type=Path, default=ALIASES)
    ap.add_argument("--taxonomy-out", type=Path, default=shared_model("audio_taxonomy.json"))
    ap.add_argument("--classes-out", type=Path, default=shared_model("birdnet_classes.json"))
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--commit", action="store_true", help="write the artefacts")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    setup_logging(args.verbose)

    labels = parse_labels(args.labels.read_text(encoding="utf-8").splitlines())
    LOG.info("%d classes in %s", len(labels), args.labels.name)

    aliases = load_aliases(args.aliases)
    if aliases:
        LOG.info("%d curated aliases", len(aliases))

    wanted = sorted(
        {aliases.get(x.scientific_name, x.scientific_name) for x in labels
         if x.scientific_name and not x.is_non_event}
    )
    LOG.info("resolving %d distinct names against the GBIF backbone", len(wanted))
    fetched = matcher(GbifClient(pause_s=0.0, pool_size=args.workers), args.workers)(wanted)

    found = resolve(labels, fetched.get, aliases)
    print("\n".join(report_lines(found, labels)))

    # The English names come off BirdNET's own labels rather than out of GBIF, which saves a
    # request per taxon and gives the name a birder would actually use.
    english = vernaculars(labels, found)
    nodes = [
        replace(node, vernacular_en=english[node.taxon_id])
        if not node.vernacular_en and node.taxon_id in english
        else node
        for node in build_taxonomy_nodes(found.taxa.values())
    ]
    taxonomy = Taxonomy(nodes)
    LOG.info("audio taxonomy: %d leaves, %d nodes", taxonomy.n_taxa, len(taxonomy))

    if not args.commit:
        print("\nNothing written. Re-run with --commit to save the artefacts.")
        return 0

    args.taxonomy_out.parent.mkdir(parents=True, exist_ok=True)
    args.taxonomy_out.write_text(
        json.dumps(taxonomy.to_dict()["taxa"], indent=1) + "\n", encoding="utf-8"
    )
    args.classes_out.write_text(
        json.dumps(
            {
                "birdnet_version": args.version,
                "region": args.region,
                "n_classes": len(labels),
                "n_resolved": len(found.taxa),
                "source": "https://huggingface.co/tphakala/BirdNET-v3.0-Models",
                "note": (
                    "Class index is the position in BirdNET's output vector and is the "
                    "contract. Rebuild on every BirdNET version bump."
                ),
                "classes": {str(k): v for k, v in class_map(found).items()},
            },
            indent=1,
        )
        + "\n",
        encoding="utf-8",
    )
    LOG.info("wrote %s and %s", args.taxonomy_out, args.classes_out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
