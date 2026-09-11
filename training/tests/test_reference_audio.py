"""Cutting a bundled clip out of a xeno-canto recording."""

from __future__ import annotations

from pathlib import Path

from lifelist_train.cli.reference_audio import (
    CLIP_SECONDS,
    credit_line,
    ffmpeg_argv,
    poor_yield,
)


def test_the_credit_names_the_recordist_and_the_recording():
    """Attribution is the licence, not a courtesy — and XC number is how somebody finds it."""
    line = credit_line({"recordist": "Someone Named", "xc_id": "507779"})

    assert "Someone Named" in line
    assert "XC507779" in line


def test_a_recording_with_no_name_says_unknown_rather_than_nothing():
    """It only reaches here under CC0, which asks for nothing — but a blank where a name goes
    reads as a bug, and "unknown" reads as a fact."""
    assert "unknown" in credit_line({"recordist": "", "xc_id": "1"})


def test_the_clip_is_mono_opus_at_the_asked_for_length():
    argv = ffmpeg_argv(Path("in.mp3"), Path("out.opus"), seconds=12)

    assert argv[:2] == ["ffmpeg", "-v"]
    assert "-t" in argv and argv[argv.index("-t") + 1] == "12"
    assert argv[argv.index("-ac") + 1] == "1"
    assert argv[argv.index("-c:a") + 1] == "libopus"


def test_only_the_leading_silence_is_trimmed():
    """`start_periods=1` cuts the run of quiet before the bird and nothing else. Trimming every
    silence would close the gaps between the phrases of a song, which is most of what makes it
    recognisable as that song."""
    filters = ffmpeg_argv(Path("in.mp3"), Path("out.opus"))[
        ffmpeg_argv(Path("in.mp3"), Path("out.opus")).index("-af") + 1
    ]

    assert "silenceremove" in filters
    assert "start_periods=1" in filters
    assert "stop_periods" not in filters


def test_the_default_clip_is_ten_seconds():
    argv = ffmpeg_argv(Path("in.mp3"), Path("out.opus"))

    assert argv[argv.index("-t") + 1] == str(CLIP_SECONDS)


def test_an_archive_that_has_stopped_answering_is_a_failed_build():
    """The specific way this shipped broken: every download failed and the build went green.

    `credits.json` was then written empty, the app found no recording for any species, and
    the comparison button simply never appeared — indistinguishable from a bird that happens
    to have no recording. One dead URL is not a failed build; seven hundred of them are.
    """
    assert poor_yield(0, 731, 0.5)
    assert poor_yield(12, 731, 0.5)
    assert not poor_yield(700, 731, 0.5)
    # A handful missing is normal and must stay normal — 64 of 795 species had no usable
    # recording at all when the index was built.
    assert not poor_yield(690, 731, 0.9)


def test_the_check_can_be_turned_off_and_never_divides_by_zero():
    assert not poor_yield(0, 731, 0)
    assert not poor_yield(0, 0, 0.5)
