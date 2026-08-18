"""Wikipedia intros for the shipped taxonomy, bundled rather than fetched at read time.

The app is offline-first and gets used in a field with no signal. An "about this" panel that
is blank exactly when you are standing in front of the animal is worse than no panel, so the
text ships in the APK. It is small: an intro paragraph per taxon, capped, is a couple of MB.

Everything that decides anything is a pure function over already-fetched JSON — which title to
ask for, how to follow a redirect, what counts as a usable extract, how the index is keyed. The
one thing that cannot be pure, the HTTP call, is injected. Same shape as `gbif.py`.

Why the Action API and not the REST summary endpoint: REST is one page per request, and an
anonymous caller gets throttled to a 25-second `Retry-After`, which makes 4,645 titles a day's
work. `action=query&prop=extracts&exlimit=max` takes **50 titles per request** — the same job
in 94 calls.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable, Sequence
from typing import Any

API = "https://en.wikipedia.org/w/api.php"

#: MediaWiki caps `prop=extracts` at 20 pages per request and says so in `limits.extracts`.
#: Ask for 50 and 30 of them come back as ordinary pages with no `extract` field — which is
#: indistinguishable, to a naive reader, from "there is no article". See `is_truncated`.
BATCH = 20
EXTRACT_LIMIT = 20
MAX_EXTRACT = 1200

#: Enough of a disambiguation page to recognise one without fetching its categories.
_DISAMBIGUATION = "may refer to"


def article_title(node: dict[str, Any], by_id: dict[int, dict[str, Any]]) -> str | None:
    """Which Wikipedia page speaks for this node.

    Root has no article. An indeterminate leaf — `Arctium sp.`, spec §1.1a — is a node this
    project invented, so it borrows its genus's page: a reader who has been told "Burdock, and
    the species is not determined" wants to read about burdocks.
    """
    if node["rank"] == "root":
        return None
    if node["taxon_id"] < 0:
        parent = by_id.get(node["parent_id"])
        return parent["scientific_name"] if parent else None
    return node["scientific_name"]


def plan_titles(nodes: Sequence[dict[str, Any]]) -> dict[str, list[int]]:
    """Distinct titles to fetch, each mapped to the taxa that will use it.

    Genus-and-its-indeterminate-leaf share a page, so asking per node would fetch some pages
    twice. Grouping first is what keeps the request count honest.
    """
    by_id = {n["taxon_id"]: n for n in nodes}
    titles: dict[str, list[int]] = {}
    for node in nodes:
        title = article_title(node, by_id)
        if title:
            titles.setdefault(title, []).append(node["taxon_id"])
    return titles


def batched(titles: Sequence[str], size: int = BATCH) -> list[list[str]]:
    if size < 1:
        raise ValueError("size must be at least 1")
    return [list(titles[i : i + size]) for i in range(0, len(titles), size)]


def build_alias_map(payload: dict[str, Any]) -> dict[str, str]:
    """`normalized` and `redirects`, flattened into one from → to map.

    The API answers under the title it landed on, not the one asked for, and it reports the
    hops separately. Without this, every redirect looks like a missing article.
    """
    query = payload.get("query") or {}
    alias: dict[str, str] = {}
    for group in ("normalized", "redirects"):
        for row in query.get(group) or []:
            alias[row["from"]] = row["to"]
    return alias


def resolve(title: str, alias: dict[str, str]) -> str:
    """Follow the alias chain, refusing to loop."""
    seen: set[str] = set()
    while title in alias and title not in seen:
        seen.add(title)
        title = alias[title]
    return title


def usable_extract(page: dict[str, Any] | None) -> str | None:
    """The intro, or None if there is nothing worth showing.

    A disambiguation page describes the word rather than the organism, and *Prunella* the plant
    genus, *Prunella* the bird genus and Prunella the singer all share a title. Showing the
    wrong one under a confident identification is worse than showing nothing.
    """
    if not page or page.get("missing"):
        return None
    extract = (page.get("extract") or "").strip()
    if not extract:
        return None
    if _DISAMBIGUATION in extract[:200].lower():
        return None
    return extract[:MAX_EXTRACT]


def mentions_taxon(extract: str, scientific_name: str) -> bool:
    """Does this article actually describe the organism we asked about?

    Needed only for the vernacular fallback. Roughly a third of Danish species have no English
    article under their binomial but do have one under their common name — asking for "Small
    tortoiseshell" instead of *Aglais urticae* recovers a large slice of them. It also opens the
    door to filing an article about a colour, a boat or a folk song under a butterfly.

    An article about the organism names it: Wikipedia's own convention is to give the binomial
    in the first sentence, in bold, next to the common name. Requiring the binomial or at least
    the genus is a cheap check that costs a handful of real articles and rejects every absurd
    one.
    """
    haystack = extract.lower()
    name = scientific_name.lower()
    if name in haystack:
        return True
    genus = name.split(" ")[0]
    # Two characters is not a genus; it is a coincidence waiting to happen.
    return len(genus) > 3 and genus in haystack


def fallback_titles(
    nodes: Sequence[dict[str, Any]],
    index: dict[str, dict[str, str]],
) -> dict[str, list[int]]:
    """English common names for the taxa that had no article under their scientific name."""
    titles: dict[str, list[int]] = {}
    for node in nodes:
        if str(node["taxon_id"]) in index:
            continue
        vernacular = node.get("vernacular_en")
        if vernacular:
            titles.setdefault(vernacular, []).append(node["taxon_id"])
    return titles


def page_url(title: str) -> str:
    return "https://en.wikipedia.org/wiki/" + title.replace(" ", "_")


def is_truncated(payload: dict[str, Any], asked: Sequence[str]) -> bool:
    """Did the API silently answer for fewer pages than we asked about?

    `prop=extracts` is capped at 20 pages per request. The response still contains all 50
    titles — the extra 30 simply have no `extract`. Treating those as "no article" is how the
    mallard went missing: a request-size mistake read as a fact about a bird.

    The cap is reported in `limits.extracts`, so this is checkable rather than guessed.
    """
    limit = (payload.get("limits") or {}).get("extracts")
    return limit is not None and len(asked) > int(limit)


def read_batch(payload: dict[str, Any], asked: Sequence[str]) -> dict[str, dict[str, str]]:
    """Turn one API response into `{asked title: article}`, dropping what has no article."""
    query = payload.get("query") or {}
    alias = build_alias_map(payload)
    # formatversion=2 returns a list; the legacy shape is a dict keyed by page id.
    raw = query.get("pages") or []
    pages = {p["title"]: p for p in (raw if isinstance(raw, list) else raw.values())}

    out: dict[str, dict[str, str]] = {}
    for title in asked:
        page = pages.get(resolve(title, alias))
        extract = usable_extract(page)
        if extract is None:
            continue
        out[title] = {
            "title": page["title"],
            "extract": extract,
            "url": page_url(page["title"]),
        }
    return out


def build_index(
    titles: dict[str, list[int]],
    articles: dict[str, dict[str, str]],
) -> dict[str, dict[str, str]]:
    """Key the articles by taxon id, so the app does no name matching at read time.

    String keys because that is what JSON gives back, and because taxon ids are negative for
    indeterminate leaves — an integer key would round-trip through JSON as a string anyway.
    """
    index: dict[str, dict[str, str]] = {}
    for title, taxon_ids in titles.items():
        article = articles.get(title)
        if article is None:
            continue
        for taxon_id in taxon_ids:
            index[str(taxon_id)] = article
    return index


def apply_fallback(
    nodes: Sequence[dict[str, Any]],
    index: dict[str, dict[str, str]],
    articles: dict[str, dict[str, str]],
) -> int:
    """Fold verified common-name articles into the index, returning how many were added.

    Verification is per *node*, not per title: one common name can belong to several taxa in
    this taxonomy, and the article can only really be about one of them.
    """
    added = 0
    for node in nodes:
        key = str(node["taxon_id"])
        if key in index:
            continue
        vernacular = node.get("vernacular_en")
        article = articles.get(vernacular) if vernacular else None
        if article and mentions_taxon(article["extract"], node["scientific_name"]):
            index[key] = article
            added += 1
    return added


def fetch_all(
    titles: Iterable[str],
    get: Callable[[list[str]], dict[str, Any] | None],
    batch_size: int = BATCH,
    on_progress: Callable[[int, int], None] | None = None,
    into: dict[str, dict[str, str]] | None = None,
    absent: list[str] | None = None,
    unreachable: list[str] | None = None,
) -> tuple[dict[str, dict[str, str]], list[str], list[str]]:
    """Fetch every title. Returns (articles, absent, unreachable).

    **`absent` and `unreachable` are different facts and must not be merged.** "Wikipedia
    answered and has no article about this" is permanent and worth caching forever. "We could
    not reach Wikipedia" is a fact about the afternoon. An earlier version of this job wrote
    both into one `missing.json`, got rate-limited on its first pass, and recorded 3,930 titles
    as having no article — including *Anas platyrhynchos*. Every later run then skipped the
    mallard because a throttle three hours earlier had been filed as a fact about the bird.

    `into`, `absent` and `unreachable` let a caller watch the results accumulate — pass its own
    containers and they fill batch by batch, so `on_progress` can checkpoint them. Without that
    the callback fires between batches but can only see what it started with, which is how a
    "resumable" run quietly saves nothing until it has already finished.
    """
    ordered = list(titles)
    articles: dict[str, dict[str, str]] = into if into is not None else {}
    missing: list[str] = absent if absent is not None else []
    failed: list[str] = unreachable if unreachable is not None else []
    batches = batched(ordered, batch_size)
    for position, batch in enumerate(batches, start=1):
        payload = get(batch)
        if payload is None or is_truncated(payload, batch):
            # A truncated response is a fact about our request, not about these titles.
            failed.extend(batch)
        else:
            found = read_batch(payload, batch)
            articles.update(found)
            missing.extend(t for t in batch if t not in found)
        if on_progress is not None:
            on_progress(position, len(batches))
    return articles, missing, failed
