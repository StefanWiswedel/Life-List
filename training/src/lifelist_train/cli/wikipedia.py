"""Bundle Wikipedia intros for the shipped taxonomy.

    lifelist-wikipedia --taxonomy shared/model/taxonomy.json \
        --out shared/model/wikipedia.json -v

Resumable: every batch is written to the cache as it lands, and re-running skips both the
titles already fetched and the ones already known to have no article. That matters because
this is a public API being asked for 4,645 pages from one address, and a run that has to
start over after a throttle is a run that never finishes.
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
    parser.add_argument("--taxonomy", default=shared_model("taxonomy.json"))
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

    taxonomy_path = Path(args.taxonomy)
    if not taxonomy_path.exists():
        LOG.error("%s not found — stage 1 writes it", taxonomy_path)
        return 1

    nodes = json.loads(taxonomy_path.read_text(encoding="utf-8"))
    titles = plan_titles(nodes)

    cache_path, missing_path = Path(args.cache), Path(args.absent)
    articles: dict[str, dict[str, str]] = load_json(cache_path, {})
    missing: set[str] = set(load_json(missing_path, []))

    todo = [t for t in sorted(titles) if t not in articles and t not in missing]
    LOG.info(
        "%d nodes, %d distinct titles — %d cached, %d known absent, %d to fetch",
        len(nodes), len(titles), len(articles), len(missing), len(todo),
    )

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
        if todo:
            get = make_getter(args.pause)
            absent2: list[str] = []
            unreachable2: list[str] = []

            def progress2(done: int, total: int) -> None:
                write_json(cache_path, articles)
                write_json(missing_path, sorted(missing | set(absent2)))
                LOG.info("common names, batch %d/%d", done, total)
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

    write_json(Path(args.out), index)
    LOG.info(
        "%d of %d nodes have an article (%.0f%%) — wrote %s",
        len(index), len(nodes), 100 * len(index) / max(len(nodes), 1), args.out,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
