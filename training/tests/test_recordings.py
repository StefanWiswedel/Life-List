"""Choosing one reference recording per species — build plan §3.6."""

from __future__ import annotations

from lifelist_train.recordings import (
    duration_seconds,
    licence_ok,
    parse,
    select,
    summarise,
    usable,
)


def record(**overrides):
    base = {
        "id": "507779",
        "gen": "Erithacus",
        "sp": "rubecula",
        "en": "European Robin",
        "rec": "Someone Named",
        "lic": "https://creativecommons.org/licenses/by-nc-sa/4.0/",
        "q": "A",
        "type": "song",
        "length": "0:32",
        "cnt": "Denmark",
        "file": "https://xeno-canto.org/507779/download",
    }
    base.update(overrides)
    return base


# -- licence, which is a filter and not a preference -----------------------------


def test_the_licences_the_photographs_already_ship_under_are_allowed():
    for licence in (
        "https://creativecommons.org/licenses/by/4.0/",
        "https://creativecommons.org/licenses/by-sa/4.0/",
        "https://creativecommons.org/licenses/by-nc/4.0/",
        "https://creativecommons.org/licenses/by-nc-sa/4.0/",
        "https://creativecommons.org/publicdomain/zero/1.0/",
    ):
        assert licence_ok(licence), licence


def test_no_licence_is_a_no_rather_than_an_unknown():
    assert not licence_ok(None)
    assert not licence_ok("")


def test_a_no_derivatives_licence_is_refused():
    """A clip is a derivative. Cutting ten seconds out of an ND recording is the thing the
    licence exists to forbid, so it never reaches the index."""
    assert not licence_ok("https://creativecommons.org/licenses/by-nc-nd/4.0/")


def test_a_recording_with_nobody_to_credit_cannot_be_shipped():
    """Every licence here but CC0 requires attribution, and we cannot give one we do not have."""
    anonymous = parse(record(rec=""))

    assert anonymous is not None and not usable(anonymous)
    assert select([record(rec="")]) is None


def test_a_public_domain_recording_needs_no_name():
    """CC0 asks for nothing, so an unattributed one is still usable — the only case where
    a missing recordist is not disqualifying."""
    chosen = select([
        record(rec="", lic="https://creativecommons.org/publicdomain/zero/1.0/")
    ])

    assert chosen is not None


# -- duration --------------------------------------------------------------------


def test_minutes_and_hours_both_parse():
    assert duration_seconds("0:32") == 32
    assert duration_seconds("3:07") == 187
    assert duration_seconds("1:02:11") == 3731


def test_an_unreadable_duration_loses_rather_than_wins():
    """Treated as very long, not as zero: otherwise a broken field looks like the shortest
    recording in the archive and wins every tie."""
    assert duration_seconds("what") > 3600
    assert duration_seconds(None) > 3600


# -- choosing --------------------------------------------------------------------


def test_the_cleanest_recording_wins():
    chosen = select([record(id="1", q="C"), record(id="2", q="A"), record(id="3", q="B")])

    assert chosen.xc_id == "2"


def test_a_song_beats_a_call_at_the_same_quality():
    chosen = select([record(id="1", type="alarm call"), record(id="2", type="song")])

    assert chosen.xc_id == "2"


def test_the_shorter_of_two_songs_wins():
    # The clip is cut from the start, so a forty-minute dawn chorus is mostly not the bird.
    chosen = select([record(id="1", length="8:20"), record(id="2", length="0:19")])

    assert chosen.xc_id == "2"


def test_a_recording_longer_than_the_cap_is_not_used_at_all():
    assert select([record(length="45:00")]) is None


def test_ties_break_on_the_id_so_a_rebuild_picks_the_same_recording():
    chosen = select([record(id="900"), record(id="100"), record(id="500")])

    assert chosen.xc_id == "100"


def test_nothing_shippable_means_nothing():
    assert select([record(lic="https://creativecommons.org/licenses/by-nc-nd/4.0/")]) is None
    assert select([]) is None


def test_a_record_missing_its_species_is_not_an_identification():
    assert select([record(sp="")]) is None


# -- the summary -----------------------------------------------------------------


def test_the_summary_says_how_encumbered_the_index_is():
    """NonCommercial is most of xeno-canto, and a number nobody printed is a number nobody
    weighed."""
    index = [
        {"quality": "A", "licence": "https://creativecommons.org/licenses/by-nc-sa/4.0/"},
        {"quality": "A", "licence": "https://creativecommons.org/licenses/by/4.0/"},
        {"quality": "B", "licence": "https://creativecommons.org/licenses/by-nc/4.0/"},
    ]

    counts = summarise(index)

    assert counts["total"] == 3
    assert counts["quality A"] == 2
    assert counts["noncommercial"] == 2
