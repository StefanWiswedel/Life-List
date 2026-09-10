"""Find the iNaturalist taxon behind each audio-only species — build plan §3.5.

    lifelist-audio-bridge -v
    lifelist-audio-bridge --commit

The reference photograph on a result screen comes from iNaturalist, and the index that picks it
is keyed by iNaturalist taxon id. `taxon_bridge.json` holds that crossing for every taxon in the
*vision* model — which is 272 of the audio taxonomy's 801 leaves (VERIFICATION §62). The other
529 are species the camera was never trained on, so nothing has ever looked them up, and a bird
identified by ear would show a blank where the comparison photograph goes.

They are resolved by scientific name against the iNaturalist API, which is the only handle
there is: the two id spaces are unrelated, and GBIF does not carry iNaturalist keys.

**A name that resolves to more than one taxon is refused, not guessed.** That is the rule that
has held since the first synonym table: picking one silently is how a beetle gets filed as a
plant. Here the cost of a wrong guess is only a wrong photograph, but a wrong photograph beside
a confident identification is precisely the thing that makes somebody trust the wrong bird.
"""

from __future__ import annotations

import argparse
import json
import time
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

from ._common import LOG, setup_logging, shared_model

API = "https://api.inaturalist.org/v1/taxa"
USER_AGENT = "LifeList/0.10 (https://github.com/StefanWiswedel/Life-List)"
PAUSE = 1.0


def leaves_of(taxonomy: Path) -> dict[int, str]:
    """GBIF id → scientific name, for every leaf. Synthetic `sp.` leaves are skipped: they are
    a genus wearing a species' clothes and iNaturalist has no such taxon."""
    return {
        int(node["taxon_id"]): str(node["scientific_name"])
        for node in json.loads(taxonomy.read_text(encoding="utf-8"))
        if node.get("leaf_index") is not None and int(node["taxon_id"]) > 0
    }


def already_bridged(bridge: Path) -> set[int]:
    """GBIF ids the existing crossing already covers."""
    if not bridge.exists():
        return set()
    mapping = json.loads(bridge.read_text(encoding="utf-8")).get("mapping") or {}
    return {int(gbif) for gbif in mapping.values()}


def pick(results: list[dict[str, Any]], name: str) -> int | None:
    """The one iNaturalist taxon that *is* this name, or nothing.

    Matching is on the exact name at species rank. iNaturalist's search is generous — asking for
    `Turdus merula` offers subspecies, and asking for a name it does not have offers whatever
    was closest — so generosity is exactly what has to be refused here.
    """
    exact = [
        r for r in results
        if str(r.get("name", "")).strip().lower() == name.strip().lower()
        and str(r.get("rank", "")).lower() in {"species", "subspecies", "genus"}
    ]
    if len(exact) != 1:
        return None
    return int(exact[0]["id"])


def resolve(
    wanted: dict[int, str],
    search: Callable[[str], list[dict[str, Any]]],
    also: Mapping[int, str] | None = None,
) -> tuple[dict[int, int], list[tuple[int, str]]]:
    """(gbif → inat) and the names that did not resolve to exactly one taxon.

    ``also`` is a second name to try — BirdNET's, where GBIF's did not land. The three
    checklists disagree in different places, and the disagreements do not line up: GBIF holds
    the wood warbler as `Phylloscopus sibillatrix`, with two l's, which iNaturalist has never
    heard of, while BirdNET's `sibilatrix` it knows perfectly well. Trying both costs one extra
    request for a name that already failed and recovers the little ringed plover, the wood
    warbler and the Tibetan sand plover — all birds a Dane might actually meet.
    """
    also = also or {}
    found: dict[int, int] = {}
    missed: list[tuple[int, str]] = []
    for gbif, name in sorted(wanted.items()):
        inat = pick(search(name), name)
        if inat is None:
            alternative = also.get(gbif)
            if alternative and alternative != name:
                inat = pick(search(alternative), alternative)
        if inat is None:
            missed.append((gbif, name))
        else:
            found[gbif] = inat
    return found, missed


def birdnet_names(classes: Path, labels: Path) -> dict[int, str]:
    """GBIF id → the name BirdNET calls it, via the class map. Missing files are not fatal."""
    if not classes.exists() or not labels.exists():
        return {}
    mapping = json.loads(classes.read_text(encoding="utf-8")).get("classes") or {}
    lines = labels.read_text(encoding="utf-8").splitlines()
    out: dict[int, str] = {}
    for index, gbif in mapping.items():
        position = int(index)
        if position < len(lines):
            name = lines[position].split("_")[0].strip()
            if name:
                out.setdefault(int(gbif), name)
    return out


def searcher(session: Any = None) -> Callable[[str], list[dict[str, Any]]]:
    import requests

    session = session or requests.Session()

    def search(name: str) -> list[dict[str, Any]]:
        for attempt in range(5):
            try:
                response = session.get(
                    API,
                    params={"q": name, "per_page": 10},
                    headers={"User-Agent": USER_AGENT},
                    timeout=60,
                )
                if response.status_code == 429:
                    time.sleep(min(float(response.headers.get("Retry-After") or 20), 60))
                    continue
                response.raise_for_status()
                time.sleep(PAUSE)  # iNaturalist asks for one request a second
                return list(response.json().get("results") or [])
            except Exception as exc:  # noqa: BLE001 — one bad name must not end the run
                LOG.debug("attempt %d for %r failed: %s", attempt, name, exc)
                time.sleep(min(5 * (attempt + 1), 60))
        LOG.warning("gave up on %r", name)
        return []

    return search


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--taxonomy", type=Path, default=shared_model("audio_taxonomy.json"))
    ap.add_argument("--bridge", type=Path, default=shared_model("taxon_bridge.json"))
    ap.add_argument("--out", type=Path, default=shared_model("audio_bridge.json"))
    ap.add_argument("--classes", type=Path, default=shared_model("birdnet_classes.json"))
    ap.add_argument(
        "--labels",
        type=Path,
        default=shared_model("").parent
        / "birdnet"
        / "birdnet-v3.0-preview3.1-western-palearctic-labels-b1.txt",
    )
    ap.add_argument("--limit", type=int, default=None, help="stop after N, for testing")
    ap.add_argument("--commit", action="store_true")
    ap.add_argument(
        "--merge",
        action="store_true",
        help="keep the crossings already in --out rather than replacing the file",
    )
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    setup_logging(args.verbose)

    leaves = leaves_of(args.taxonomy)
    covered = already_bridged(args.bridge)
    if args.merge:
        # A rerun asks only about the ones that failed, which is what makes a second pass with
        # a better name cheap enough to be worth trying.
        covered |= already_bridged(args.out)
    wanted = {gbif: name for gbif, name in leaves.items() if gbif not in covered}
    if args.limit:
        wanted = dict(sorted(wanted.items())[: args.limit])
    LOG.info(
        "%d audio leaves, %d already crossed by %s, %d to resolve",
        len(leaves), len(leaves) - len(wanted), args.bridge.name, len(wanted),
    )

    found, missed = resolve(wanted, searcher(), birdnet_names(args.classes, args.labels))
    LOG.info("resolved %d of %d", len(found), len(wanted))
    if missed:
        print("\nNo single iNaturalist taxon for these — they will have no reference photo:")
        for gbif, name in missed:
            print(f"  [{gbif}] {name}")

    if args.out.exists() and args.merge:
        # A rerun should not throw away the ones that already landed to re-ask for them — but
        # it must drop any whose taxon the tree no longer has. A crossing kept past its taxon
        # is worse than a missing one: it points a reference photograph at a leaf that does not
        # exist, and the taxon that *should* have had it silently loses it (§67).
        previous = json.loads(args.out.read_text(encoding="utf-8"))
        stale = 0
        for inat, gbif in (previous.get("mapping") or {}).items():
            if int(gbif) not in leaves:
                stale += 1
                continue
            found.setdefault(int(gbif), int(inat))
        if stale:
            LOG.info("dropped %d crossings whose taxon is no longer a leaf", stale)
        LOG.info("merged with %s: %d crossings", args.out.name, len(found))

    if not args.commit:
        print("\nNothing written. Re-run with --commit.")
        return 0

    args.out.write_text(
        json.dumps(
            {
                "note": (
                    "GBIF -> iNaturalist for the audio-only species, resolved by scientific "
                    "name. Same shape as taxon_bridge.json so lifelist-reference-index can "
                    "read both."
                ),
                "mapping": {str(inat): gbif for gbif, inat in sorted(found.items())},
                "unresolved": {str(gbif): name for gbif, name in missed},
            },
            indent=1,
        )
        + "\n",
        encoding="utf-8",
    )
    LOG.info("wrote %s", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
