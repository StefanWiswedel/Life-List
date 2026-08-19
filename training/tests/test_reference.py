"""Choosing a reference photograph — the rule, without a network.

Reported as "the reference images look a bit crap". Two faults: the downloader asked for 240 px
and the choice was whichever training photo came first. This covers the second; the first was a
constant.
"""

from __future__ import annotations

from lifelist_train.reference import (
    MIN_DIMENSION,
    big_enough,
    build_index,
    credit_of,
    curated_photo,
    extension_of,
    licence_label,
    select,
    shippable,
    summarise,
)


def photo(**kwargs):
    base = {
        "id": 1,
        "license_code": "cc-by",
        "attribution": "(c) Someone, some rights reserved (CC BY)",
        "original_dimensions": {"width": 2048, "height": 1536},
        "medium_url": "https://example.org/photos/1/medium.jpeg",
    }
    base.update(kwargs)
    return {"photo": base}


# -- licences ---------------------------------------------------------------

def test_the_free_licences_ship():
    for code in ("cc0", "CC-BY", "cc-by-nc", "cc-by-sa", "cc-by-nc-sa"):
        assert shippable(code), code


def test_all_rights_reserved_does_not():
    # iNaturalist leaves license_code null for these, and may display what we may not
    # redistribute. Absent is a no, not an unknown.
    assert not shippable(None)
    assert not shippable("")


def test_no_derivatives_does_not_ship():
    # The pipeline re-encodes what it downloads, and a re-encode is a derivative however
    # small. Shipping an ND photo through a JPEG encoder is the kind of thing nobody notices.
    assert not shippable("cc-by-nd")
    assert not shippable("cc-by-nc-nd")


def test_licence_labels_read_the_way_a_credit_line_should():
    assert licence_label("cc-by-nc") == "CC BY-NC"
    assert licence_label("cc0") == "CC0"


# -- attribution ------------------------------------------------------------

def test_the_photographer_is_pulled_out_of_the_attribution_string():
    assert credit_of(
        {"attribution": "(c) Matt Lavin, some rights reserved (CC BY-SA)"}
    ) == "Matt Lavin"


def test_a_cc0_upload_credits_the_uploader():
    assert credit_of(
        {"attribution": "no rights reserved, uploaded by Donald Hobern"}
    ) == "Donald Hobern"


def test_an_empty_attribution_yields_nobody():
    assert credit_of({"attribution": ""}) is None
    assert credit_of({}) is None


# -- size -------------------------------------------------------------------

def test_a_thumbnail_is_refused():
    assert not big_enough(photo(original_dimensions={"width": 200, "height": 150})["photo"])


def test_a_missing_dimension_is_not_held_against_it():
    # Older iNaturalist records omit dimensions; the downloader asks for a fixed size anyway.
    assert big_enough(photo(original_dimensions=None)["photo"])


# -- the choice itself ------------------------------------------------------

def test_the_curated_order_is_respected_rather_than_re_sorted():
    # The community put its best picture first. Picking the largest instead would be
    # substituting our judgement on a question they are better placed to answer.
    photos = [
        photo(id=10, original_dimensions={"width": 800, "height": 600}),
        photo(id=11, original_dimensions={"width": 4000, "height": 3000}),
    ]
    assert curated_photo(photos)["photo_id"] == 10


def test_an_unlicensed_favourite_is_skipped_for_the_next_one_we_may_use():
    # The commonest real case: iNaturalist's own face for the taxon is all rights reserved.
    photos = [photo(id=10, license_code=None), photo(id=11, license_code="cc-by-nc")]
    assert curated_photo(photos)["photo_id"] == 11


def test_a_photo_with_no_photographer_is_skipped():
    photos = [photo(id=10, attribution=""), photo(id=11)]
    assert curated_photo(photos)["photo_id"] == 11


def test_nothing_shippable_yields_nothing():
    assert curated_photo([photo(id=10, license_code=None)]) is None


def test_the_extension_comes_from_the_url_because_s3_keys_are_exact():
    assert extension_of({"medium_url": "https://x/photos/1/medium.jpeg"}) == "jpeg"
    assert extension_of({"medium_url": "https://x/photos/1/medium.png?size=2"}) == "png"
    assert extension_of({}) == "jpeg"


# -- falling back -----------------------------------------------------------

FALLBACK = {"photo_id": 999, "extension": "jpg", "licence": "CC-BY-NC", "credit": "Old Pick"}


def test_a_taxon_with_no_usable_curated_photo_keeps_what_it_had():
    # Something beside the user's own photograph beats nothing. The training-set picture is a
    # real photograph of the right organism; it was merely unchosen.
    chosen = select({"taxon_photos": [photo(id=10, license_code=None)]}, FALLBACK)

    assert chosen["photo_id"] == 999
    assert chosen["source"] == "training-manifest"


def test_a_curated_photo_wins_over_the_old_pick():
    chosen = select({"taxon_photos": [photo(id=10)]}, FALLBACK)

    assert chosen["photo_id"] == 10
    assert chosen["source"] == "inaturalist-curated"


def test_no_curated_photo_and_no_fallback_means_no_entry():
    assert select({"taxon_photos": []}, None) is None


# -- the shipped index ------------------------------------------------------

def test_the_index_is_keyed_by_gbif_and_remembers_the_inat_id():
    # The two id spaces are unrelated, and the mapping cost a join over 334k photo rows to
    # recover once. It is recorded so it never has to be recovered again.
    index = build_index(
        pairs=[(1688020, 324160)],
        taxa={324160: {"taxon_photos": [photo(id=10)]}},
        fallbacks={},
    )

    assert index == [
        {
            "taxon_id": 1688020,
            "inat_taxon_id": 324160,
            "photo_id": 10,
            "extension": "jpeg",
            "licence": "CC BY",
            "credit": "Someone",
            "source": "inaturalist-curated",
        }
    ]


def test_the_index_is_sorted_so_a_rebuild_shows_a_readable_diff():
    index = build_index(
        pairs=[(20, 2), (10, 1)],
        taxa={1: {"taxon_photos": [photo(id=1)]}, 2: {"taxon_photos": [photo(id=2)]}},
        fallbacks={},
    )

    assert [e["taxon_id"] for e in index] == [10, 20]


def test_a_taxon_with_nothing_at_all_is_left_out_rather_than_shipped_blank():
    index = build_index(pairs=[(10, 1)], taxa={1: {"taxon_photos": []}}, fallbacks={})

    assert index == []


def test_the_summary_counts_where_each_photo_came_from():
    index = [{"source": "inaturalist-curated"}, {"source": "training-manifest"}]

    assert summarise(index) == {"taxa": 2, "curated": 1, "fallback": 1}


def test_the_minimum_is_bigger_than_what_used_to_ship():
    assert MIN_DIMENSION > 240
