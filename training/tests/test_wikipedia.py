"""Bundled Wikipedia intros — the parts that decide anything.

Nothing here touches the network. What is worth testing is which page a node asks for, what
happens when Wikipedia redirects, and what counts as an extract worth showing — and all three
are pure functions over JSON that has already arrived.
"""

from __future__ import annotations

import pytest

from lifelist_train.wikipedia import (
    article_title,
    batched,
    build_alias_map,
    build_index,
    fallback_titles,
    fetch_all,
    is_truncated,
    mentions_taxon,
    plan_titles,
    read_batch,
    resolve,
    usable_extract,
)

NODES = [
    {"taxon_id": 0, "parent_id": None, "rank": "root", "scientific_name": "Life"},
    {"taxon_id": 5, "parent_id": 0, "rank": "family", "scientific_name": "Carabidae"},
    {"taxon_id": 9, "parent_id": 5, "rank": "genus", "scientific_name": "Carabus"},
    {"taxon_id": 11, "parent_id": 9, "rank": "species", "scientific_name": "Carabus nemoralis"},
    {"taxon_id": -9, "parent_id": 9, "rank": "species", "scientific_name": "Carabus sp."},
]
BY_ID = {n["taxon_id"]: n for n in NODES}


# -- which page speaks for a node -------------------------------------------

def test_root_has_no_article():
    assert article_title(NODES[0], BY_ID) is None


def test_a_real_taxon_asks_for_its_own_name():
    assert article_title(NODES[3], BY_ID) == "Carabus nemoralis"


def test_an_indeterminate_leaf_borrows_its_genus():
    # `Carabus sp.` is a node this project invented; Wikipedia has never heard of it, and a
    # reader told "a ground beetle, species undetermined" wants to read about the genus.
    assert article_title(NODES[4], BY_ID) == "Carabus"


def test_a_shared_page_is_fetched_once():
    titles = plan_titles(NODES)
    assert sorted(titles) == ["Carabidae", "Carabus", "Carabus nemoralis"]
    assert sorted(titles["Carabus"]) == [-9, 9]


# -- batching ---------------------------------------------------------------

def test_batches_cover_everything_exactly_once():
    items = [str(i) for i in range(127)]
    chunks = batched(items, 50)
    assert [len(c) for c in chunks] == [50, 50, 27]
    assert [x for c in chunks for x in c] == items


def test_a_zero_batch_is_refused_rather_than_looping_forever():
    with pytest.raises(ValueError):
        batched(["a"], 0)


# -- redirects --------------------------------------------------------------

def test_a_redirect_chain_resolves():
    alias = build_alias_map(
        {"query": {
            "normalized": [{"from": "carabus nemoralis", "to": "Carabus nemoralis"}],
            "redirects": [{"from": "Carabus nemoralis", "to": "Bronze carabid"}],
        }}
    )
    assert resolve("carabus nemoralis", alias) == "Bronze carabid"


def test_a_redirect_loop_terminates():
    assert resolve("A", {"A": "B", "B": "A"}) in {"A", "B"}


def test_a_redirected_page_is_found_rather_than_counted_missing():
    payload = {
        "query": {
            "redirects": [{"from": "Carabus nemoralis", "to": "Bronze carabid"}],
            "pages": [{"title": "Bronze carabid", "extract": "A ground beetle of Europe."}],
        }
    }
    found = read_batch(payload, ["Carabus nemoralis"])
    assert found["Carabus nemoralis"]["title"] == "Bronze carabid"
    assert found["Carabus nemoralis"]["url"].endswith("/Bronze_carabid")


# -- what is worth showing --------------------------------------------------

def test_a_missing_page_yields_nothing():
    assert usable_extract({"title": "Nope", "missing": True}) is None
    assert usable_extract(None) is None


def test_an_empty_extract_yields_nothing():
    assert usable_extract({"title": "Stub", "extract": "   "}) is None


def test_a_disambiguation_page_is_refused():
    # Prunella is a plant genus, a bird genus and a singer. Showing the wrong one under a
    # confident identification is worse than showing nothing at all.
    page = {"title": "Prunella", "extract": "Prunella may refer to: Prunella (plant)..."}
    assert usable_extract(page) is None


def test_a_long_extract_is_capped():
    page = {"title": "Long", "extract": "x" * 5000}
    assert len(usable_extract(page)) == 1200


# -- the shipped index ------------------------------------------------------

def test_the_index_is_keyed_by_taxon_id_including_negative_ones():
    titles = plan_titles(NODES)
    articles = {"Carabus": {"title": "Carabus", "extract": "A genus.", "url": "u"}}
    index = build_index(titles, articles)
    assert set(index) == {"9", "-9"}
    assert index["-9"]["extract"] == "A genus."


def test_taxa_without_an_article_are_simply_absent():
    index = build_index(plan_titles(NODES), {})
    assert index == {}


# -- fetching ---------------------------------------------------------------

def test_one_failed_batch_does_not_lose_the_others():
    calls = []

    def get(batch):
        calls.append(batch)
        if "b" in batch:
            return None  # the network, being the network
        return {"query": {"pages": [{"title": t, "extract": f"About {t}."} for t in batch]}}

    articles, missing, failed = fetch_all(["a", "b", "c"], get, batch_size=1)
    assert sorted(articles) == ["a", "c"]
    assert len(calls) == 3
    # The distinction that matters: "b" was never asked, so it must not be filed as a title
    # Wikipedia has no article about. That mistake cost the mallard its article once already.
    assert missing == []
    assert failed == ["b"]


def test_an_answered_title_with_no_article_is_absent_not_unreachable():
    articles, missing, failed = fetch_all(
        ["Nonesuch"],
        lambda batch: {"query": {"pages": [{"title": "Nonesuch", "missing": True}]}},
        batch_size=1,
    )
    assert articles == {}
    assert missing == ["Nonesuch"]
    assert failed == []


def test_progress_is_reported_per_batch():
    seen = []
    fetch_all(
        [str(i) for i in range(5)],
        lambda batch: {"query": {"pages": []}},
        batch_size=2,
        on_progress=lambda done, total: seen.append((done, total)),
    )
    assert seen == [(1, 3), (2, 3), (3, 3)]


def test_results_accumulate_into_the_callers_containers():
    # The CLI checkpoints to disk from `on_progress`, which is only useful if the containers
    # it writes are the ones being filled. Returning everything at the end would make a
    # "resumable" run save nothing until it had already finished.
    articles: dict = {}
    absent: list = []
    seen = []

    fetch_all(
        ["a", "b"],
        lambda batch: {"query": {"pages": [{"title": t, "extract": f"About {t}."} for t in batch]}}
        if batch != ["b"] else None,
        batch_size=1,
        on_progress=lambda done, total: seen.append((len(articles), len(absent))),
        into=articles,
        absent=absent,
    )

    assert seen == [(1, 0), (1, 0)]


# -- the vernacular fallback ------------------------------------------------

def test_an_article_that_names_the_binomial_is_accepted():
    assert mentions_taxon(
        "Aglais urticae, the small tortoiseshell, is a butterfly.", "Aglais urticae"
    )


def test_an_article_that_only_names_the_genus_is_accepted():
    assert mentions_taxon(
        "The small tortoiseshell is a butterfly in the genus Aglais.", "Aglais urticae"
    )


def test_an_article_about_something_else_entirely_is_rejected():
    # "Small tortoiseshell" is also a cat coat and a hair comb. A butterfly identification
    # that opens an article about combs is worse than one that opens nothing.
    assert not mentions_taxon(
        "Tortoiseshell is a material produced from the shells of turtles.", "Aglais urticae"
    )


def test_a_three_letter_genus_is_not_matched_on_its_own():
    # Genus 'Apus' would be fine; a genus like 'Ala' would match half the dictionary.
    assert not mentions_taxon("A tale of a small animal.", "Ala minor")


def test_the_fallback_only_covers_taxa_that_are_still_missing():
    nodes = [
        {"taxon_id": 1, "rank": "species", "scientific_name": "A b", "vernacular_en": "Alpha"},
        {"taxon_id": 2, "rank": "species", "scientific_name": "C d", "vernacular_en": "Beta"},
        {"taxon_id": 3, "rank": "species", "scientific_name": "E f", "vernacular_en": None},
    ]
    index = {"1": {"title": "A b", "extract": "x", "url": "u"}}
    assert fallback_titles(nodes, index) == {"Beta": [2]}


# -- the extracts cap -------------------------------------------------------

def test_a_truncated_response_is_recognised():
    # MediaWiki answers for all 50 titles but only fills in 20 extracts, and says so.
    payload = {"limits": {"extracts": 20}, "query": {"pages": []}}
    assert is_truncated(payload, [str(i) for i in range(50)])


def test_a_full_response_is_not_truncated():
    payload = {"limits": {"extracts": 20}, "query": {"pages": []}}
    assert not is_truncated(payload, [str(i) for i in range(20)])


def test_a_response_with_no_reported_limit_is_trusted():
    assert not is_truncated({"query": {"pages": []}}, ["a", "b"])


def test_a_truncated_batch_is_unreachable_rather_than_absent():
    # The whole point: an over-sized request must never teach the cache that Anas
    # platyrhynchos has no Wikipedia article.
    articles, missing, failed = fetch_all(
        ["Anas platyrhynchos", "Vulpes vulpes"],
        lambda batch: {"limits": {"extracts": 1}, "query": {"pages": []}},
        batch_size=2,
    )
    assert articles == {}
    assert missing == []
    assert failed == ["Anas platyrhynchos", "Vulpes vulpes"]


def test_the_default_batch_never_exceeds_the_extracts_cap():
    from lifelist_train.wikipedia import BATCH, EXTRACT_LIMIT

    assert BATCH <= EXTRACT_LIMIT
