"""A photograph for every Danish species, not only the ones the model knows — §83.

    lifelist-checklist-photos -v

`lifelist-reference-index` does this for the model's own 3,482 species, and crosses to
iNaturalist through `taxon_bridge.json` — a mapping built during training and only ever
covering taxa the model was trained on. The checklist has 26,722, so the crossing has to come
from somewhere else.

**iNaturalist's open-data taxa table, not their search API.** One 38 MB download gives every
active taxon's name and id, which is 22,605 of our 26,722 matched by exact scientific name with
no API calls at all. Eight names match two active iNaturalist species and are **refused rather
than guessed** — the same rule the bridge follows, and for the same reason: *Prunella* is a
plant and a bird.

The photographs themselves still come from the API, because the curated per-taxon order is not
in the open-data export — but batched thirty ids at a time, which is 754 requests rather than
22,605. `reference.py` picks which photo: the first in iNaturalist's own curated order that we
are licensed to redistribute and that is big enough to be a reference plate rather than a
thumbnail.

83% of the crossed species have one. The rest get no photograph and say so, which for a
checklist that exists to show you what you have *not* found is a much smaller problem than a
checklist that only lists what a model was trained on.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import json
import time
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from ..reference import big_enough, credit_of, extension_of, shippable
from ._common import LOG, setup_logging, shared_model, write_json

TAXA_URL = "https://inaturalist-open-data.s3.amazonaws.com/taxa.csv.gz"
API = "https://api.inaturalist.org/v1/taxa"
USER_AGENT = "LifeList/0.12 (https://github.com/StefanWiswedel/Life-List)"

#: iNaturalist asks for restraint and this is a one-off bulk read of their catalogue, so the
#: batch size does the work instead of the request rate: thirty taxa per call, four at a time.
BATCH = 30
WORKERS = 4


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checklist", type=Path, default=shared_model("checklist.json"))
    parser.add_argument("--out", type=Path, default=shared_model("checklist_photos.json"))
    parser.add_argument("--taxa", type=Path, default=Path("cache/inat_taxa.csv.gz"))
    parser.add_argument("--cache", type=Path, default=Path("cache/checklist_photos.jsonl"))
    parser.add_argument(
        "--skip-model",
        action="store_true",
        default=True,
        help="leave out species the model already ships a 500px photograph for",
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def cross(taxa: Path, wanted: dict[str, int]) -> tuple[dict[int, int], dict[str, list[int]]]:
    """Scientific name → iNaturalist id, from the open-data taxa table.

    Returns the unambiguous crossings and, separately, the names that matched more than one
    active iNaturalist species. The second list is **not** resolved by picking one: a homonym
    silently resolved is how a beetle gets filed as a plant (§28).
    """
    found: dict[str, list[int]] = {}
    with gzip.open(taxa, "rt", encoding="utf-8") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            if row["rank"] != "species" or row["active"] != "true":
                continue
            if row["name"] in wanted:
                found.setdefault(row["name"], []).append(int(row["taxon_id"]))
    one = {wanted[name]: ids[0] for name, ids in found.items() if len(ids) == 1}
    many = {name: ids for name, ids in found.items() if len(ids) > 1}
    return one, many


def pick(taxon: dict[str, Any]) -> dict[str, Any] | None:
    """The first curated photograph we may ship, or None."""
    photos = [entry.get("photo") or {} for entry in (taxon.get("taxon_photos") or [])]
    return next(
        (p for p in photos if shippable(p.get("license_code")) and big_enough(p)),
        None,
    )


def row_for(taxon: dict[str, Any], gbif: int) -> dict[str, Any]:
    chosen = pick(taxon)
    return {
        "taxon_id": gbif,
        "inat_taxon_id": int(taxon["id"]),
        "photo_id": int(chosen["id"]) if chosen else None,
        "extension": extension_of(chosen) if chosen else None,
        "licence": (
            str(chosen.get("license_code") or "").upper().replace("CC-", "CC ")
            if chosen else None
        ),
        "credit": credit_of(chosen) if chosen else None,
        # Taken while we are here: iNaturalist has an English name for 11,155 of these, and
        # 352 of them are ones GBIF had none for (§71 did the same for the model's species).
        "vernacular_en": taxon.get("preferred_common_name"),
        "source": "inaturalist-curated",
    }


def cached(path: Path) -> dict[int, dict[str, Any]]:
    if not path.exists():
        return {}
    out: dict[int, dict[str, Any]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            if row.get("taxon_id") is not None:
                out[int(row["taxon_id"])] = row
    return out


def fetch(pairs: Iterable[tuple[int, int]], path: Path, session) -> dict[int, dict[str, Any]]:
    """Curated photo lists, thirty taxa a call, resumable."""
    have = cached(path)
    todo = [(gbif, inat) for gbif, inat in pairs if gbif not in have]
    if not todo:
        LOG.info("photos: all %d cached", len(have))
        return have
    LOG.info("photos: %d cached, %d to fetch", len(have), len(todo))
    by_inat = {inat: gbif for gbif, inat in todo}
    chunks = [todo[i : i + BATCH] for i in range(0, len(todo), BATCH)]
    path.parent.mkdir(parents=True, exist_ok=True)

    def one(chunk: list[tuple[int, int]]) -> list[dict[str, Any]]:
        ids = ",".join(str(inat) for _, inat in chunk)
        for attempt in range(3):
            try:
                answer = session.get(
                    f"{API}/{ids}", headers={"User-Agent": USER_AGENT}, timeout=45
                )
                if answer.status_code == 429:
                    time.sleep(5 * (attempt + 1))
                    continue
                answer.raise_for_status()
                return answer.json().get("results", [])
            except Exception:  # noqa: BLE001 — one bad batch must not end the run
                time.sleep(2 * (attempt + 1))
        return []

    started = time.time()
    with path.open("a", encoding="utf-8") as sink, ThreadPoolExecutor(WORKERS) as pool:
        for position, results in enumerate(pool.map(one, chunks), start=1):
            for taxon in results:
                gbif = by_inat.get(int(taxon["id"]))
                if gbif is None:
                    continue
                row = row_for(taxon, gbif)
                sink.write(json.dumps(row, ensure_ascii=False) + "\n")
                have[gbif] = row
            if position % 100 == 0:
                LOG.info("  batch %d/%d", position, len(chunks))
            time.sleep(0.25)
    LOG.info("photos: %.1f min", (time.time() - started) / 60)
    return have


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    document = json.loads(args.checklist.read_text(encoding="utf-8"))
    species = document["species"]
    wanted = {
        entry["name"]: int(key)
        for key, entry in species.items()
        if not (args.skip_model and entry.get("model"))
    }
    LOG.info("%d checklist species want a photograph", len(wanted))

    if not args.taxa.exists():
        LOG.error(
            "no %s — download it once: curl -o %s %s", args.taxa, args.taxa, TAXA_URL
        )
        return 1
    crossed, homonyms = cross(args.taxa, wanted)
    LOG.info(
        "%d crossed to iNaturalist by name; %d refused as homonyms; %d have no active "
        "iNaturalist species of that name",
        len(crossed),
        len(homonyms),
        len(wanted) - len(crossed) - len(homonyms),
    )
    for name, ids in list(homonyms.items())[:10]:
        LOG.warning("  homonym, not guessed: %s -> %s", name, ids)

    import requests
    from requests.adapters import HTTPAdapter

    session = requests.Session()
    session.mount("https://", HTTPAdapter(pool_connections=WORKERS, pool_maxsize=WORKERS))

    rows = fetch(sorted(crossed.items()), args.cache, session)
    with_photo = [row for row in rows.values() if row.get("photo_id")]
    write_json(args.out, sorted(with_photo, key=lambda row: row["taxon_id"]))

    named = sum(1 for row in rows.values() if row.get("vernacular_en"))
    LOG.info(
        "%s: %d of %d crossed species have a licensed curated photograph (%d%%); "
        "%d carry an English name",
        args.out,
        len(with_photo),
        len(rows),
        100 * len(with_photo) // max(len(rows), 1),
        named,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
