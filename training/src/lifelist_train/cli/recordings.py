"""Pick one reference recording per species from xeno-canto — build plan §3.6.

    XENOCANTO_KEY=... lifelist-recordings -v
    XENOCANTO_KEY=... lifelist-recordings --commit

Writes `shared/model/reference_audio.json`: which recording stands for each species, who
recorded it and under what terms. The bytes are not fetched here — CI does that on a tagged
build and cuts a short clip, exactly as it does for the 3,462 reference photographs.

**The key is never written anywhere.** It arrives in the environment, is used, and is not
recorded in the index, the log line or the artefact. A credential in a public repository is a
credential somebody else has.

Resumable, because it is 795 requests over a public API and the last long run against a public
API died two thirds of the way through when a proxy restarted. Every 25 species the index so
far is written out; a second run starts from what is already there.
"""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path
from typing import Any

from ..recordings import entry, select, summarise
from ._common import LOG, setup_logging, shared_model

API = "https://xeno-canto.org/api/3/recordings"
USER_AGENT = "LifeList/0.10 (https://github.com/StefanWiswedel/Life-List)"
PAUSE = 1.0
CHECKPOINT = 25


def leaves_of(taxonomy: Path) -> dict[int, str]:
    """GBIF id → scientific name for every real species leaf."""
    return {
        int(node["taxon_id"]): str(node["scientific_name"])
        for node in json.loads(taxonomy.read_text(encoding="utf-8"))
        if node.get("leaf_index") is not None and int(node["taxon_id"]) > 0
    }


def searcher(key: str, session: Any = None) -> Any:
    import logging

    import requests

    # urllib3 logs the full request URL at DEBUG, and the key is a query parameter — so `-v`
    # printed the credential on every one of 795 lines, into a log somebody would paste into a
    # bug report without thinking. The API takes the key nowhere but the query string, so the
    # fix is to stop the library from narrating it.
    logging.getLogger("urllib3").setLevel(logging.INFO)

    session = session or requests.Session()

    def search(name: str) -> list[dict[str, Any]]:
        # Quality A and B only. C and below is xeno-canto's own way of saying the bird is in
        # there somewhere behind a motorway, which is not a reference for anything.
        query = f'sp:"{name}" q:">C"'
        for attempt in range(5):
            try:
                response = session.get(
                    API,
                    params={"query": query, "key": key},
                    headers={"User-Agent": USER_AGENT},
                    timeout=60,
                )
                if response.status_code == 429:
                    time.sleep(min(float(response.headers.get("Retry-After") or 20), 60))
                    continue
                response.raise_for_status()
                time.sleep(PAUSE)
                return list(response.json().get("recordings") or [])
            except Exception as exc:  # noqa: BLE001 — one bad name must not end the run
                LOG.debug("attempt %d for %r failed: %s", attempt, name, exc)
                time.sleep(min(5 * (attempt + 1), 60))
        LOG.warning("gave up on %r", name)
        return []

    return search


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--taxonomy", type=Path, default=shared_model("audio_taxonomy.json"))
    ap.add_argument("--out", type=Path, default=shared_model("reference_audio.json"))
    ap.add_argument("--limit", type=int, default=None, help="stop after N, for testing")
    ap.add_argument("--commit", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    setup_logging(args.verbose)

    key = os.environ.get("XENOCANTO_KEY", "").strip()
    if not key:
        LOG.error(
            "no XENOCANTO_KEY in the environment. Get one from xeno-canto.org/account and "
            "pass it in the environment — never on the command line, where it lands in a "
            "shell history."
        )
        return 1

    leaves = leaves_of(args.taxonomy)
    existing = {
        int(row["taxon_id"]): row
        for row in (json.loads(args.out.read_text(encoding="utf-8")) if args.out.exists() else [])
    }
    todo = sorted((gbif, name) for gbif, name in leaves.items() if gbif not in existing)
    if args.limit:
        todo = todo[: args.limit]
    LOG.info("%d species, %d already indexed, %d to look up", len(leaves), len(existing), len(todo))

    search = searcher(key)
    found = dict(existing)
    missing: list[tuple[int, str]] = []

    def save() -> None:
        args.out.write_text(
            json.dumps([found[k] for k in sorted(found)], indent=1) + "\n", encoding="utf-8"
        )

    for done, (gbif, name) in enumerate(todo, start=1):
        chosen = select(search(name))
        if chosen is None:
            missing.append((gbif, name))
        else:
            found[gbif] = entry(gbif, chosen)
        if args.commit and done % CHECKPOINT == 0:
            save()
            LOG.info("%d/%d — %d recordings", done, len(todo), len(found))

    index = [found[k] for k in sorted(found)]
    print(f"\n{len(index)} of {len(leaves)} species have a recording")
    for label, count in sorted(summarise(index).items()):
        print(f"  {label}: {count}")
    if missing:
        print(f"\nNo shippable recording for {len(missing)}:")
        for gbif, name in missing[:40]:
            print(f"  [{gbif}] {name}")
        if len(missing) > 40:
            print(f"  ... and {len(missing) - 40} more")

    if not args.commit:
        print("\nNothing written. Re-run with --commit.")
        return 0

    save()
    LOG.info("wrote %s", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
