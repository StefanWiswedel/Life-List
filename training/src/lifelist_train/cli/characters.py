"""Mine the differences between look-alikes out of the articles we already fetch.

    lifelist-characters --out shared/model/characters.json -v

The app ships Wikipedia *intros* (§31, §87) and throws the rest away. The diagnostics are in
the rest:

    Vespula germanica — "unlike the common wasp, has three tiny black dots on the clypeus"
    Vespula vulgaris  — "each has only one black mark on its clypeus, usually anchor or
                         dagger-shaped"
    and then          — "can sometimes appear broken... making it look extremely similar"

That third sentence is why this extracts and quotes rather than summarising. A sentence
paraphrased by me is an error nobody can trace; a sentence quoted with its URL is Wikipedia's
error, visible and fixable, and the caveat survives. See VERIFICATION.md §95.

Only genera where the camera can name two or more species are asked about — the rest can never
produce the ambiguity this answers. 258 of those are insects; the commonest 50 cover 77% of
Denmark's insect records.
"""

from __future__ import annotations

import argparse
import json
import re
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from ._common import LOG, add_common_args, setup_logging, shared_model

USER_AGENT = "LifeList/0.20 (https://github.com/StefanWiswedel/Life-List)"
API = "https://en.wikipedia.org/w/api.php"

#: A sentence that *compares*. Merely naming a body part is description, not a discriminator.
COMPARES = re.compile(
    r"\b(distinguish\w*|differ\w*|tell(?:ing)? (?:it |them )?apart|unlike|"
    r"separated from|can be told|confused with|in contrast|whereas|"
    r"compared (?:to|with)|similar species)\b",
    re.I,
)

#: A bare "similar to" is not a comparison between species. "The upper wing-coverts are
#: brownish-black and similar to the outer primaries" is a true sentence about one bird and no
#: help whatsoever in telling it from another — and it was the commonest thing the first
#: version of this pulled out of a "Similar species" section.
#: Parenthesised, which is Wikipedia's own convention for naming the other animal — "the
#: paler-winged first-winter ring ouzel (Turdus torquatus)". An unparenthesised match is
#: useless: `[A-Z][a-z]+ [a-z]+` also matches "The upper", which is how the first attempt kept
#: a sentence about one thrush's own wing-coverts.
OTHER_SPECIES = re.compile(r"\([A-Z][a-z]{3,} [a-z]{3,}\)")

#: …and mentions something you could look at.
PART = re.compile(
    r"\b(clypeus|antenna\w*|wing\w*|thorax|abdomen|tibia|tarsi|legs?|spots?|bands?|stripes?|"
    r"markings?|hairs?|scales?|underside|upperside|forewing|hindwing|face|eyes?|tail|rump|"
    r"bill|beak|crown|throat|flank|mandible|pronotum|elytra|sternite|tergite)\b",
    re.I,
)

#: Where the character lives, and therefore which photograph would settle it. Ordered: the
#: first match wins, so the most specific view is offered.
VIEWS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"\bclypeus|\bface\b|\bfrons\b", re.I), "the face, from the front"),
    (re.compile(r"\bunderside|\bventral", re.I), "the underside"),
    (re.compile(r"\bhindwing|\bhind wing", re.I), "the hindwing"),
    (re.compile(r"\bforewing|\bfore wing", re.I), "the forewing, spread if you can"),
    (re.compile(r"\bantenna", re.I), "the antennae, close up"),
    (re.compile(r"\btibia|\btarsi|\bleg", re.I), "the legs"),
    (re.compile(r"\bpronotum|\bthorax", re.I), "the thorax from above"),
    (re.compile(r"\belytra|\babdomen|\btergite|\bsternite", re.I), "the abdomen from above"),
]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--taxonomy", type=Path, default=shared_model("taxonomy.json"))
    parser.add_argument("--checklist", type=Path, default=shared_model("checklist.json"))
    parser.add_argument("--out", type=Path, default=shared_model("characters.json"))
    parser.add_argument("--cache", type=Path, default=Path("cache/characters.jsonl"))
    parser.add_argument(
        "--seed",
        type=Path,
        default=shared_model("..") / "characters-seed.json",
        help=(
            "hand-checked structured questions, each quoting a source. Merged over whatever "
            "was extracted, because a question with named options is worth more than a "
            "paragraph and cannot yet be derived from one reliably."
        ),
    )
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument(
        "--limit", type=int, default=0, help="fetch only the N commonest genera; 0 for all"
    )
    parser.add_argument(
        "--group",
        type=int,
        default=None,
        help=(
            "a GBIF key to fetch within — 216 for insects, 212 for birds. Ranking every genus "
            "together ranks them by Danish *recording effort*, which is birdwatchers: the "
            "first run took the global top 60 and got 38 genera of birds, which the camera "
            "already names to species. The ambiguity this answers is overwhelmingly insects. "
            "See VERIFICATION.md section 96."
        ),
    )
    return add_common_args(parser)


def ambiguous_genera(
    taxonomy: list[dict], records: dict[int, int]
) -> list[tuple[int, str, list[dict]]]:
    """Genera holding two or more species the camera can name, commonest first."""
    by_id = {int(n["taxon_id"]): n for n in taxonomy}

    def genus_of(node: dict) -> int | None:
        parent = node.get("parent_id")
        while parent is not None:
            up = by_id.get(int(parent))
            if up is None:
                return None
            if up.get("rank") == "genus":
                return int(up["taxon_id"])
            parent = up.get("parent_id")
        return None

    grouped: dict[int, list[dict]] = defaultdict(list)
    for node in taxonomy:
        if node.get("rank") == "species" and int(node["taxon_id"]) > 0:
            key = genus_of(node)
            if key is not None:
                grouped[key].append(node)
    out = [
        (key, by_id[key]["scientific_name"], members)
        for key, members in grouped.items()
        if len(members) >= 2 and key in by_id
    ]
    out.sort(key=lambda row: -sum(records.get(int(m["taxon_id"]), 0) for m in row[2]))
    return out


def article(title: str, session) -> str:
    params = {
        "action": "query", "format": "json", "formatversion": 2,
        "prop": "extracts", "explaintext": 1, "redirects": 1, "titles": title,
    }
    for attempt in range(4):
        try:
            response = session.get(
                API, params=params, headers={"User-Agent": USER_AGENT}, timeout=60
            )
            if response.status_code == 429:
                time.sleep(min(float(response.headers.get("Retry-After") or 20), 60))
                continue
            response.raise_for_status()
            return response.json()["query"]["pages"][0].get("extract") or ""
        except Exception as exc:  # noqa: BLE001 — one bad title must not end the run
            LOG.debug("%s attempt %d: %s", title, attempt, exc)
            time.sleep(2 * (attempt + 1))
    return ""


def _compares(sentence: str) -> bool:
    """Does this sentence set one species against another, rather than describe one?"""
    return bool(COMPARES.search(sentence) or OTHER_SPECIES.search(sentence))


def differences(text: str, limit: int = 3) -> list[str]:
    """The sentences that compare this species with another, preferring the section that says so.

    Wikipedia has a `== Similar species ==` convention and a section written for this exact
    purpose is better evidence than a sentence that merely happens to contain "unlike".
    """
    section = ""
    match = re.search(r"==+\s*Similar species\s*==+\n(.*?)(?=\n==[^=]|\Z)", text, re.S | re.I)
    if match:
        section = match.group(1)
    # The section first, the whole article second. A section written for this purpose is
    # better evidence than a sentence elsewhere that happens to say "unlike".
    hay = f"{section}\n{text}" if section else text
    sentences = re.split(r"(?<=[.!?])\s+", hay.replace("\n", " "))
    out = []
    for sentence in sentences:
        sentence = " ".join(sentence.split())
        if not (40 < len(sentence) < 320):
            continue
        # A comparative cue is required *even inside* a "Similar species" section. Those
        # sections carry plain description too — "the upper wing-coverts are brownish-black
        # and similar to the outer primaries" is a true sentence about one bird and no help at
        # all in telling it from another. A sentence that does not compare is clutter, and
        # clutter on this screen costs the same patience a bad question would.
        if _compares(sentence) and PART.search(sentence):
            out.append(sentence)
        if len(out) >= limit:
            break
    return out


def view_for(sentences: list[str]) -> str:
    joined = " ".join(sentences)
    for pattern, view in VIEWS:
        if pattern.search(joined):
            return view
    return ""


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    taxonomy = json.loads(args.taxonomy.read_text(encoding="utf-8"))
    checklist = json.loads(args.checklist.read_text(encoding="utf-8")).get("species", {})
    records = {int(k): int(v.get("records") or 0) for k, v in checklist.items()}
    genera = ambiguous_genera(taxonomy, records)
    by_id = {int(n["taxon_id"]): n for n in taxonomy}

    def under(node: dict, ancestor: int) -> bool:
        parent = node.get("parent_id")
        while parent is not None:
            if int(parent) == ancestor:
                return True
            parent = (by_id.get(int(parent)) or {}).get("parent_id")
        return False

    # Selection decides what to *fetch*. The guides are built from everything cached, below,
    # so a second run for a different group adds to the file rather than replacing it.
    selected = genera
    if args.group is not None:
        selected = [row for row in selected if under(by_id[row[0]], args.group)]
    if args.limit:
        selected = selected[: args.limit]
    wanted = {int(m["taxon_id"]): m["scientific_name"] for _, _, ms in selected for m in ms}
    LOG.info(
        "%d ambiguous genera in all, %d selected, %d species to read",
        len(genera), len(selected), len(wanted),
    )

    cached: dict[int, dict] = {}
    if args.cache.exists():
        for line in args.cache.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                cached[int(row["taxon_id"])] = row
    todo = [(t, n) for t, n in wanted.items() if t not in cached]
    LOG.info("%d cached, %d to fetch", len(cached), len(todo))

    if todo:
        import requests

        session = requests.Session()

        def one(pair: tuple[int, str]) -> dict:
            taxon, name = pair
            text = article(name, session)
            found = differences(text)
            slug = name.replace(" ", "_")
            return {
                "taxon_id": taxon, "name": name, "differences": found,
                "source": f"https://en.wikipedia.org/wiki/{slug}" if found else "",
            }

        args.cache.parent.mkdir(parents=True, exist_ok=True)
        # The fetch is allowed to die; the write is not. Three half-hour runs once banked
        # 23,015 titles and produced no file at all because the output only happened at the
        # end of a completed run (§86, §87). Wikipedia throttles, so this will be interrupted.
        try:
            pool = ThreadPoolExecutor(args.workers)
            with args.cache.open("a", encoding="utf-8") as sink, pool:
                for position, row in enumerate(pool.map(one, todo), start=1):
                    sink.write(json.dumps(row, ensure_ascii=False) + "\n")
                    sink.flush()
                    cached[int(row["taxon_id"])] = row
                    if position % 200 == 0:
                        LOG.info("  %d/%d", position, len(todo))
        except KeyboardInterrupt:
            LOG.warning("interrupted — writing the guides from what is cached")
        except Exception as exc:  # noqa: BLE001 — a partial read still makes a usable file
            LOG.warning("fetch stopped (%s) — writing from what is cached", type(exc).__name__)

    guides = {}
    with_text = 0
    for key, name, members in genera:  # every ambiguous genus, not only this run's selection
        rows = [cached.get(int(m["taxon_id"]), {}) for m in members]
        # Filtered again on the way out, not only on the way in.
        #
        # The cache holds extracted sentences rather than the article they came from, so
        # sharpening the extraction would otherwise mean re-fetching 256 pages from an API
        # that throttles. Re-applying the test here is free and exact, because every later
        # rule has been a *narrowing* of the first one. It is a patch over a design mistake
        # worth naming: a cache should hold the input, so the thing it makes can be improved
        # without asking the network again (§95).
        told = []
        for row in rows:
            kept = [
                sentence for sentence in row.get("differences", ())
                if _compares(sentence) and PART.search(sentence)
            ]
            if kept:
                told.append({**row, "differences": kept})
        if not told:
            continue
        with_text += 1
        guides[str(key)] = {
            "genus": name,
            "view": view_for([s for r in told for s in r["differences"]]),
            "differences": [
                {
                    "taxon_id": r["taxon_id"], "name": r["name"],
                    "says": r["differences"], "source": r["source"],
                }
                for r in told
            ],
            "characters": [],
        }

    # The hand-checked questions, merged over the prose. Each one quotes a source that was
    # read; nothing here is written from memory (§95).
    seeded = 0
    if args.seed.exists():
        for key, guide in json.loads(args.seed.read_text(encoding="utf-8")).items():
            into = guides.setdefault(key, {"genus": guide.get("genus", ""), "differences": []})
            into["characters"] = guide.get("characters", [])
            if guide.get("view"):
                into["view"] = guide["view"]
            into.setdefault("view", "")
            seeded += 1
    elif args.seed:
        LOG.warning("no %s — shipping prose only, no structured questions", args.seed)

    args.out.write_text(json.dumps(guides, ensure_ascii=False), encoding="utf-8")
    LOG.info(
        "%s: %d genera, %d with quoted differences, %d with questions, %d with a view",
        args.out, len(guides), with_text, seeded,
        sum(1 for g in guides.values() if g.get("view")),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
