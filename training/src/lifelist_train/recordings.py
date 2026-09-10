"""Choosing one reference recording per species — build plan §3.6.

The result screen shows a reference photograph beside what the model said, so you can look at
both and disagree (§37). Sound wants the same: hearing the bird the app named, next to the five
seconds it actually heard, is how somebody decides for themselves rather than taking a number
on trust.

Pure selection over already-fetched records, as `reference.py` is for photographs. The client
lives in the CLI, and everything worth being wrong about is here where it can be tested.

**Licence is a hard filter, not a preference.** Every recording in the app carries its
recordist's name and terms into the credits screen — the obligation BUILD.md §8 called real
before there was a screen to put it on.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any

#: Licences that may be bundled. The same set the photographs already ship under, NonCommercial
#: included: `reference.py` has allowed `cc-by-nc` since the first index, so an audio policy
#: stricter than the picture policy would be a distinction without a difference.
SHIPPABLE = ("cc0", "publicdomain", "by/", "by-sa/", "by-nc/", "by-nc-sa/")

#: Xeno-canto's own quality grades. A is "loud and clear"; E is barely audible.
QUALITY_ORDER = {"A": 0, "B": 1, "C": 2, "D": 3, "E": 4}

#: What a person is most likely to recognise. Song first — it is what a bird is doing when you
#: hear it from a garden — then calls, then whatever else was recorded.
SOUND_ORDER = ("song", "call")

#: A clip is cut from the start of the recording, so a very long file is mostly the wrong part
#: of itself. Ten minutes of a dawn chorus is not a reference for one bird.
MAX_SECONDS = 600


@dataclass(frozen=True, slots=True)
class Recording:
    """One xeno-canto recording, reduced to what the app needs and must credit."""

    xc_id: str
    url: str
    scientific_name: str
    english_name: str | None
    recordist: str
    licence: str
    quality: str
    sound_type: str
    seconds: int
    country: str | None


def licence_ok(licence: str | None) -> bool:
    """Whether this licence lets the recording into the app.

    Xeno-canto gives a full URL — `https://creativecommons.org/licenses/by-nc-sa/4.0/` — so the
    test is on the path rather than on a short code. An absent licence is a no, not an unknown.
    """
    if not licence:
        return False
    text = str(licence).lower()
    return any(marker in text for marker in SHIPPABLE)


def duration_seconds(length: str | None) -> int:
    """`"3:07"` or `"1:02:11"` → seconds. Anything unparseable is treated as very long.

    Not zero: an unreadable duration must lose to a readable one rather than win by looking
    like the shortest recording in the archive.
    """
    if not length or not re.fullmatch(r"[\d:]+", str(length)):
        return MAX_SECONDS * 10
    parts = [int(p or 0) for p in str(length).split(":")]
    seconds = 0
    for part in parts:
        seconds = seconds * 60 + part
    return seconds


def sound_rank(sound_type: str | None) -> int:
    text = str(sound_type or "").lower()
    for index, marker in enumerate(SOUND_ORDER):
        if marker in text:
            return index
    return len(SOUND_ORDER)


def parse(record: Mapping[str, Any]) -> Recording | None:
    """One API record, or nothing if it cannot be shipped or cannot be identified."""
    if not licence_ok(record.get("lic")):
        return None
    xc_id = str(record.get("id") or "").strip()
    url = str(record.get("file") or "").strip()
    genus, species = str(record.get("gen") or "").strip(), str(record.get("sp") or "").strip()
    if not xc_id or not url or not genus or not species:
        return None

    return Recording(
        xc_id=xc_id,
        url=url,
        scientific_name=f"{genus} {species}",
        english_name=(str(record.get("en")).strip() or None) if record.get("en") else None,
        # The recordist is the credit. A recording with nobody to credit cannot be used, because
        # every licence in SHIPPABLE except CC0 requires attribution and we cannot give it.
        recordist=str(record.get("rec") or "").strip(),
        licence=str(record.get("lic") or "").strip(),
        quality=str(record.get("q") or "").strip().upper(),
        sound_type=str(record.get("type") or "").strip(),
        seconds=duration_seconds(record.get("length")),
        country=(str(record.get("cnt")).strip() or None) if record.get("cnt") else None,
    )


#: Waivers that ask for nothing. Spelled as they appear in the licence URL — xeno-canto links
#: `creativecommons.org/publicdomain/zero/1.0/`, where the string "cc0" never occurs.
PUBLIC_DOMAIN = ("cc0", "publicdomain")


def usable(recording: Recording) -> bool:
    """A recording nobody can be credited for cannot be shipped, unless nobody has to be."""
    licence = recording.licence.lower()
    return bool(recording.recordist) or any(marker in licence for marker in PUBLIC_DOMAIN)


def select(records: Iterable[Mapping[str, Any]]) -> Recording | None:
    """The one recording to bundle, or nothing.

    Ordered by what a listener actually wants: a clean recording first, then a song rather than
    an alarm call, then a short one — a clip is cut from the start, so a forty-minute file is
    mostly not the bird you are looking for. Ties break on the xeno-canto id so a rebuild picks
    the same recording rather than reshuffling the whole index for no reason.
    """
    candidates = [
        parsed for parsed in (parse(record) for record in records)
        if parsed is not None and usable(parsed) and parsed.seconds <= MAX_SECONDS
    ]
    if not candidates:
        return None
    return min(
        candidates,
        key=lambda r: (
            QUALITY_ORDER.get(r.quality, len(QUALITY_ORDER)),
            sound_rank(r.sound_type),
            r.seconds,
            int(r.xc_id) if r.xc_id.isdigit() else 0,
        ),
    )


def entry(taxon_id: int, recording: Recording) -> dict[str, Any]:
    """One line of the index, in the shape the app reads."""
    return {
        "taxon_id": taxon_id,
        "xc_id": recording.xc_id,
        "url": recording.url,
        "scientific_name": recording.scientific_name,
        "recordist": recording.recordist,
        "licence": recording.licence,
        "quality": recording.quality,
        "type": recording.sound_type,
        "seconds": recording.seconds,
        "country": recording.country,
    }


def summarise(index: Sequence[Mapping[str, Any]]) -> dict[str, int]:
    """Counts worth printing: how many, how good, and how encumbered."""
    out: dict[str, int] = {"total": len(index)}
    for row in index:
        out[f"quality {row.get('quality') or '?'}"] = (
            out.get(f"quality {row.get('quality') or '?'}", 0) + 1
        )
    out["noncommercial"] = sum(1 for row in index if "nc" in str(row.get("licence", "")).lower())
    return out
