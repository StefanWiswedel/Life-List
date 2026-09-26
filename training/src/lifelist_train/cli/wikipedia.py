"""Bundle Wikipedia intros for the shipped taxonomy.

    lifelist-wikipedia --taxonomy shared/model/taxonomy.json \
        --out shared/model/wikipedia.json -v

Resumable: every batch is written to the cache as it lands, and re-running skips both the
titles already fetched and the ones already known to have no article. That matters because
this is a public API being asked for 4,645 pages from one address, and a run that has to
start over after a throttle is a run that never finishes.

And *publishable at any point*: the bundle is written after the binomial pass and again on
every batch of the common-name pass, so an interrupted run leaves the best file it could
build rather than none. `--from-cache` skips both fetches and just writes.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

from ..wikipedia import (
    API,
    BATCH,
    apply_fallback,
    build_index,
    fallback_titles,
    fetch_all,
    plan_titles,
)
from ._common import LOG, add_common_args, setup_logging, shared_model

USER_AGENT = "LifeList/0.6 (https://github.com/StefanWiswedel/Life-List)"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Bundle Wikipedia intros for every taxon")
    parser.add_argument(
        "--taxonomy",
        action="append",
        help="a taxonomy whose nodes want an article. Repeatable; defaults to the vision and "
             "audio trees, because a bird identified by ear deserves the same paragraph as one "
             "identified by eye",
    )
    parser.add_argument("--out", default=shared_model("wikipedia.json"))
    parser.add_argument(
        "--cache",
        default="cache/wikipedia_articles.json",
        help="fetched articles, so an interrupted run resumes instead of restarting",
    )
    parser.add_argument(
        "--absent",
        default="cache/wikipedia_absent.json",
        help=(
            "titles Wikipedia answered about and has no article for, so they are not asked "
            "again. Only ever written when the API actually replied — a throttle is not a "
            "fact about a species."
        ),
    )
    parser.add_argument(
        "--checklist",
        default=None,
        help=(
            "Denmark's checklist, whose species also want a paragraph. Defaults to the shipped "
            "one where it exists: an index that shows you what you have *not* found is a much "
            "better page to land on when it can also tell you what the thing is."
        ),
    )
    parser.add_argument(
        "--from-cache",
        action="store_true",
        help=(
            "write the bundle from what is already cached and fetch nothing, in either pass. "
            "The fetch is resumable but the *write* only happened at the end of a completed "
            "run, so three half-hour runs killed by a timeout banked 23,015 resolved titles "
            "and produced no file at all. See VERIFICATION.md section 86."
        ),
    )
    parser.add_argument("--batch-size", type=int, default=BATCH)
    parser.add_argument(
        "--no-vernacular-fallback",
        action="store_true",
        help="skip the second pass that asks for English common names",
    )
    parser.add_argument(
        "--pause",
        type=float,
        default=2.0,
        help="seconds between batches. Being a good citizen is also the fastest route.",
    )
    return add_common_args(parser)


def make_getter(pause: float) -> Any:
    """One session, and a real back-off when told to wait."""
    import requests

    session = requests.Session()

    def get(titles: list[str]) -> dict[str, Any] | None:
        params = {
            "action": "query",
            "format": "json",
            "formatversion": 2,
            "prop": "extracts",
            "exintro": 1,
            "explaintext": 1,
            "exlimit": "max",
            "redirects": 1,
            "titles": "|".join(titles),
        }
        for attempt in range(8):
            try:
                response = session.get(
                    API, params=params, headers={"User-Agent": USER_AGENT}, timeout=60
                )
                if response.status_code == 429:
                    wait = min(float(response.headers.get("Retry-After") or 30), 60)
                    LOG.debug("throttled, waiting %.0f s", wait)
                    time.sleep(wait)
                    continue
                response.raise_for_status()
                return response.json()
            except Exception as exc:  # noqa: BLE001 — one bad batch must not end the run
                LOG.debug("attempt %d failed: %s", attempt, exc)
                time.sleep(min(5 * (attempt + 1), 60))
        return None

    return get


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    taxonomy_paths = [
        Path(path) for path in (
            args.taxonomy or [shared_model("taxonomy.json"), shared_model("audio_taxonomy.json")]
        )
    ]
    present = [path for path in taxonomy_paths if path.exists()]
    if not present:
        LOG.error("none of %s found — stage 1 writes them", [str(p) for p in taxonomy_paths])
        return 1
    for path in taxonomy_paths:
        if path not in present:
            LOG.warning("no %s — skipping it", path)

    # Merged by taxon_id: the two trees share 272 taxa and a node in both wants one article.
    merged: dict[int, dict] = {}
    for path in present:
        for node in json.loads(path.read_text(encoding="utf-8")):
            merged.setdefault(int(node["taxon_id"]), node)

    # The checklist is not a taxonomy — it has no tree, only species and the family each sits
    # in — so it is flattened into the same node shape rather than given its own code path.
    # `setdefault` keeps the model's own node where a species is in both, because that one
    # carries the vernacular the title fallback uses.
    checklist = Path(args.checklist) if args.checklist else shared_model("checklist.json")
    if Path(checklist).exists():
        document = json.loads(Path(checklist).read_text(encoding="utf-8"))
        for key, entry in document.get("species", {}).items():
            merged.setdefault(int(key), {
                "taxon_id": int(key),
                "parent_id": entry.get("family"),
                "rank": "species",
                "scientific_name": entry["name"],
                "vernacular_en": entry.get("vernacular_en"),
            })
        for key, entry in document.get("families", {}).items():
            merged.setdefault(int(key), {
                "taxon_id": int(key),
                "parent_id": None,
                "rank": "family",
                "scientific_name": entry["name"],
                "vernacular_en": entry.get("vernacular_en"),
            })
        LOG.info("checklist %s folded in", checklist)
    elif args.checklist:
        LOG.warning("no %s — skipping it", checklist)

    nodes = list(merged.values())
    titles = plan_titles(nodes)

    cache_path, missing_path = Path(args.cache), Path(args.absent)
    articles: dict[str, dict[str, str]] = load_json(cache_path, {})
    missing: set[str] = set(load_json(missing_path, []))

    todo = [t for t in sorted(titles) if t not in articles and t not in missing]
    LOG.info(
        "%d nodes, %d distinct titles — %d cached, %d known absent, %d to fetch",
        len(nodes), len(titles), len(articles), len(missing), len(todo),
    )

    if todo and args.from_cache:
        LOG.info(
            "--from-cache: writing %d cached articles, leaving %d unfetched",
            len(articles), len(todo),
        )
        todo = []

    if todo:
        get = make_getter(args.pause)

        absent: list[str] = []
        unreachable: list[str] = []

        def progress(done: int, total: int) -> None:
            # `articles` and `absent` are the live containers fetch_all is filling, so this
            # checkpoints what has actually arrived rather than what was on disk at startup.
            # `unreachable` is deliberately *not* saved: it is a fact about the network.
            write_json(cache_path, articles)
            write_json(missing_path, sorted(missing | set(absent)))
            LOG.info("batch %d/%d — %d articles", done, total, len(articles))
            time.sleep(args.pause)

        fetch_all(
            todo, get, args.batch_size, on_progress=progress,
            into=articles, absent=absent, unreachable=unreachable,
        )
        missing.update(absent)
        write_json(cache_path, articles)
        write_json(missing_path, sorted(missing))
        if unreachable:
            LOG.warning(
                "%d titles could not be fetched at all — re-run to try them again",
                len(unreachable),
            )

    index = build_index(titles, articles)

    def publish(stage: str) -> None:
        """Write the bundle. Called at every point where it is worth having, not once."""
        write_json(Path(args.out), index)
        LOG.info(
            "%s — %d of %d nodes have an article (%.0f%%), wrote %s",
            stage, len(index), len(nodes),
            100 * len(index) / max(len(nodes), 1), args.out,
        )

    # Before the fallback pass, not only after it. The fallback is a second fetch of
    # thousands of titles and can be killed the same way the first one was, and when it is,
    # a bundle built from every binomial that did resolve is still the best file we have.
    publish("binomials")

    # Second pass. Roughly a third of Danish species have no English article under their
    # binomial but do have one under their common name — *Aglais urticae* is a redlink,
    # "Small tortoiseshell" is not. Each candidate must name the taxon in its own text
    # before it is accepted, so a butterfly cannot end up illustrated by a hair comb.
    if not args.no_vernacular_fallback:
        second = fallback_titles(nodes, index)
        todo = [t for t in sorted(second) if t not in articles and t not in missing]
        LOG.info(
            "%d taxa still unmatched — trying %d common names",
            len(nodes) - len(index), len(todo),
        )
        if todo and args.from_cache:
            # --from-cache has to stop *both* fetches. Stopping only the first one is how a
            # flag added to make an interrupted run produce a file spent half an hour in the
            # second pass and produced no file (§86, twice).
            LOG.info("--from-cache: leaving %d common names unfetched", len(todo))
            todo = []
        if todo:
            get = make_getter(args.pause)
            absent2: list[str] = []
            unreachable2: list[str] = []

            def progress2(done: int, total: int) -> None:
                write_json(cache_path, articles)
                write_json(missing_path, sorted(missing | set(absent2)))
                # apply_fallback only fills nodes the index has no article for, so calling it
                # every batch is monotone: the file on disk is always a valid bundle holding
                # everything verified so far.
                apply_fallback(nodes, index, articles)
                write_json(Path(args.out), index)
                LOG.info("common names, batch %d/%d — %d nodes", done, total, len(index))
                time.sleep(args.pause)

            fetch_all(
                todo, get, args.batch_size, on_progress=progress2,
                into=articles, absent=absent2, unreachable=unreachable2,
            )
            missing.update(absent2)
            if unreachable2:
                LOG.warning("%d common names unreachable — re-run", len(unreachable2))
            write_json(cache_path, articles)
            write_json(missing_path, sorted(missing))
        added = apply_fallback(nodes, index, articles)
        LOG.info("%d taxa matched by common name", added)
        publish("with common names")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
