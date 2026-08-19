"""Choosing the photograph the identification screen compares against.

Reported from the field: "the reference images still always look a bit crap — doesn't really
have a good picture of the specimen, or it's very blurry, or both."

Two separate faults, and it is worth keeping them apart.

**Resolution.** The downloader asked S3 for `small`, which is 240 px. The result screen draws it
across the full width of a phone — about 1,230 physical pixels on a 3× display — and the
full-screen viewer draws it larger still. A 240 px JPEG upscaled fivefold is not a photograph of
a moth, it is a rumour of one. That is most of "blurry" and it was a one-word constant.

**Selection.** The photo was whichever one the manifest happened to list first for that taxon —
a training photograph, chosen by nobody, often a specimen in a hand at an angle or half out of
frame. Training data and a reference plate are different jobs.

The fix for the second is not cleverness, it is asking someone who already knows.
iNaturalist keeps a **curated, community-ordered list of photographs per taxon**
(`taxon_photos`), and the first entry is what the site itself shows as that taxon's face. That
is exactly the "tag that makes it work as a hero image" the request asked for, and it exists for
almost every taxon we ship.

The only catch is licensing: the curated favourite is frequently *all rights reserved*, because
iNaturalist may display it and we may not redistribute it. So this walks the curated order and
takes the first photograph we are actually allowed to ship — the best available answer to "what
does this species look like", constrained by what is ours to give away.

Everything here is a pure function over already-fetched JSON, so the rule is testable without a
network. The fetching lives in the CLI.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from typing import Any

#: Licences we may redistribute inside an APK.
#:
#: `nd` is deliberately absent. The pipeline re-encodes what it downloads, and a re-encode is a
#: derivative work however small the change. Shipping a no-derivatives photo through a JPEG
#: encoder is the kind of thing nobody notices and nobody should do.
SHIPPABLE_LICENCES = frozenset(
    {"cc0", "cc-by", "cc-by-nc", "cc-by-sa", "cc-by-nc-sa"}
)

#: Below this the photograph is worse than what we already ship. 240 px was the old size.
MIN_DIMENSION = 400


def shippable(license_code: str | None) -> bool:
    """Whether this licence lets the photograph into the app.

    A missing code means all rights reserved on iNaturalist — the field is only populated when
    the photographer chose a licence. Absent is a no, not an unknown.
    """
    return bool(license_code) and str(license_code).lower() in SHIPPABLE_LICENCES


def big_enough(photo: Mapping[str, Any], minimum: int = MIN_DIMENSION) -> bool:
    """Reject a thumbnail masquerading as a reference plate."""
    dimensions = photo.get("original_dimensions") or {}
    width = int(dimensions.get("width") or 0)
    height = int(dimensions.get("height") or 0)
    if not width or not height:
        # iNaturalist omits dimensions on some older records. Not a reason to refuse a
        # curated photograph — the downloader asks for a fixed size anyway.
        return True
    return max(width, height) >= minimum


def extension_of(photo: Mapping[str, Any]) -> str:
    """`jpeg`, `png`… taken from the URL, because S3 keys are exact.

    Getting this wrong is a 404 rather than a wrong picture, which is at least loud.
    """
    url = str(photo.get("medium_url") or photo.get("url") or "")
    tail = url.rsplit("/", 1)[-1]
    if "." in tail:
        return tail.rsplit(".", 1)[-1].split("?")[0].lower()
    return "jpeg"


def credit_of(photo: Mapping[str, Any]) -> str | None:
    """The photographer, from iNaturalist's attribution string.

    Reads `(c) Name, some rights reserved (CC BY-NC), uploaded by Someone`. The name is what a
    licence requires be shown; the rest is boilerplate we render ourselves.
    """
    attribution = str(photo.get("attribution") or "").strip()
    if not attribution:
        return None
    name = attribution
    for prefix in ("(c) ", "© "):
        if name.startswith(prefix):
            name = name[len(prefix):]
    name = name.split(",")[0].strip()
    if name.lower().startswith("no rights reserved"):
        # CC0 uploads say "no rights reserved, uploaded by <name>".
        marker = "uploaded by "
        if marker in attribution:
            return attribution.split(marker, 1)[1].split(",")[0].strip() or None
        return None
    return name or None


def licence_label(license_code: str | None) -> str:
    """`cc-by-nc` → `CC BY-NC`. What the credit line under the photograph shows."""
    if not license_code:
        return "All rights reserved"
    code = str(license_code).lower()
    if code == "cc0":
        return "CC0"
    return code.replace("cc-", "CC ", 1).upper().replace("CC ", "CC ", 1)


def curated_photo(
    taxon_photos: Sequence[Mapping[str, Any]],
    minimum: int = MIN_DIMENSION,
) -> dict[str, Any] | None:
    """The first photograph in iNaturalist's curated order that we may ship.

    Order is the whole point and is not re-sorted. The community put its best picture first;
    picking the largest, or the sharpest, or the newest would be substituting our judgement for
    theirs on a question they are better placed to answer.
    """
    for entry in taxon_photos:
        photo = entry.get("photo") if "photo" in entry else entry
        if not photo:
            continue
        if not shippable(photo.get("license_code")):
            continue
        if not big_enough(photo, minimum):
            continue
        credit = credit_of(photo)
        if not credit:
            # A licence that requires attribution, with nobody to attribute, is unusable.
            # CC0 needs no credit, but we show one anyway and would rather skip than lie.
            continue
        return {
            "photo_id": int(photo["id"]),
            "extension": extension_of(photo),
            "licence": licence_label(photo.get("license_code")),
            "credit": credit,
            "source": "inaturalist-curated",
        }
    return None


def select(
    taxon: Mapping[str, Any],
    fallback: Mapping[str, Any] | None,
    minimum: int = MIN_DIMENSION,
) -> dict[str, Any] | None:
    """The reference photograph for one taxon: curated if possible, previous pick if not.

    Falling back rather than dropping matters. A taxon with no shippable curated photo still
    needs *something* beside the user's own picture, and the training-set photo we already ship
    is a real photograph of the right organism — merely unchosen.
    """
    curated = curated_photo(taxon.get("taxon_photos") or [], minimum)
    if curated is not None:
        return curated
    if fallback is None:
        return None
    kept = dict(fallback)
    kept["source"] = "training-manifest"
    return kept


def build_index(
    pairs: Iterable[tuple[int, int]],
    taxa: Mapping[int, Mapping[str, Any]],
    fallbacks: Mapping[int, Mapping[str, Any]],
    minimum: int = MIN_DIMENSION,
) -> list[dict[str, Any]]:
    """The shipped index: one entry per taxon, keyed by *GBIF* id.

    `pairs` is (gbif_id, inat_id). The two id spaces are unrelated — GBIF 1688020 and iNat
    1688020 are different organisms — and the mapping is recorded in the index itself so a
    later rebuild never has to reconstruct it by joining photo ids again.
    """
    out: list[dict[str, Any]] = []
    for gbif_id, inat_id in pairs:
        chosen = select(taxa.get(inat_id, {}), fallbacks.get(gbif_id), minimum)
        if chosen is None:
            continue
        out.append({"taxon_id": gbif_id, "inat_taxon_id": inat_id, **chosen})
    return sorted(out, key=lambda e: e["taxon_id"])


def summarise(index: Sequence[Mapping[str, Any]]) -> dict[str, int]:
    """What changed, for a run to print rather than a human to eyeball."""
    return {
        "taxa": len(index),
        "curated": sum(1 for e in index if e.get("source") == "inaturalist-curated"),
        "fallback": sum(1 for e in index if e.get("source") == "training-manifest"),
    }
