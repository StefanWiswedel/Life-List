#!/usr/bin/env python3
"""Generate (or verify) the cross-language golden fixture for audio — spec §4A.

The sibling of `gen_golden.py`, and for the same reason: `core/.../Audio.kt` has to agree with
`lifelist_train.audio` exactly, and without a committed fixture a drift on the Python side would
leave the Kotlin parity test passing happily against the new wrong answer.

Audio needs its own fixture rather than more cases in the rollup one, because the parts that can
drift are the parts §4 does not have: which classes end up in a confusion set, and whether the
geographic prior is applied before or after that set is built. The second is the one worth
guarding — applying the prior first is a silent mask wearing a prior's clothes, it is a two-line
mistake, and nothing in the rollup fixture would notice.

    python tools/gen_golden_audio.py           # write
    python tools/gen_golden_audio.py --check   # fail if the committed file is stale (CI)
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "training" / "src"))
sys.path.insert(0, str(REPO / "training" / "tests"))

from test_audio import CHIFFCHAFF, FROG, ROBIN, WILLOW, build_soundscape  # noqa: E402

from lifelist_train.audio import identify_window  # noqa: E402

OUT = REPO / "shared" / "golden" / "golden_audio.json"

#: name, scores, threshold, detection_threshold, margin, geo, geo_weight
CASES: list[tuple[str, dict[int, float], float, float, float, dict[int, float] | None, float]] = [
    (
        # The case the whole design exists for: two warblers a poor recording cannot separate.
        "confusable_congeners_resolve_to_genus",
        {CHIFFCHAFF: 0.90, WILLOW: 0.85, ROBIN: 0.05},
        0.70, 0.25, 0.5, None, 1.0,
    ),
    (
        "a_clear_singer_reaches_species",
        {CHIFFCHAFF: 0.95, WILLOW: 0.10, ROBIN: 0.05},
        0.70, 0.25, 0.5, None, 1.0,
    ),
    (
        # A frog and a warbler at once are two detections, not two candidates for one.
        "a_frog_and_a_warbler_are_two_detections",
        {CHIFFCHAFF: 0.80, FROG: 0.78},
        0.70, 0.25, 0.5, None, 1.0,
    ),
    (
        # The prior outvotes an out-of-season bird without removing it from the set.
        "the_prior_outvotes_but_does_not_erase",
        {CHIFFCHAFF: 0.95, WILLOW: 0.92, ROBIN: 0.05},
        0.70, 0.25, 0.5, {CHIFFCHAFF: 0.05, WILLOW: 0.95, ROBIN: 0.5}, 1.0,
    ),
    (
        # Ordering, made visible: built prior-first, the willow warbler would have fallen
        # below the margin and vanished. Built to spec, it is still in the confusion set.
        "a_vagrant_survives_a_hostile_prior",
        {CHIFFCHAFF: 0.97, WILLOW: 0.95},
        0.70, 0.25, 0.5, {CHIFFCHAFF: 0.99, WILLOW: 0.000001}, 1.0,
    ),
    (
        "geo_weight_zero_disables_the_prior",
        {CHIFFCHAFF: 0.90, WILLOW: 0.85},
        0.70, 0.25, 0.5, {CHIFFCHAFF: 0.01, WILLOW: 0.99}, 0.0,
    ),
    (
        "a_wide_margin_takes_in_the_whole_genus",
        {CHIFFCHAFF: 0.90, WILLOW: 0.30, ROBIN: 0.35},
        0.70, 0.25, 0.3, None, 1.0,
    ),
    (
        # §4A.3's absent outcome: with nothing to confuse it with, the app is exactly as
        # sure as BirdNET was — 0.99 reaches the species.
        "a_lone_detection_carries_its_own_score",
        {CHIFFCHAFF: 0.99, ROBIN: 0.01},
        0.70, 0.25, 0.5, None, 1.0,
    ),
    (
        # The same arithmetic on a sound scraping over the detection threshold: refused,
        # where renormalising across the set alone would have said "chiffchaff, 100%".
        "a_weak_lone_detection_is_refused",
        {CHIFFCHAFF: 0.26, ROBIN: 0.01},
        0.70, 0.25, 0.5, None, 1.0,
    ),
]


def build() -> dict:
    tax = build_soundscape()
    cases = []
    for name, scores, threshold, detection_threshold, margin, geo, geo_weight in CASES:
        found = identify_window(
            tax,
            scores,
            threshold=threshold,
            detection_threshold=detection_threshold,
            margin=margin,
            geo=geo,
            geo_weight=geo_weight,
        )
        cases.append(
            {
                "name": name,
                "scores": {str(k): v for k, v in scores.items()},
                "threshold": threshold,
                "detection_threshold": detection_threshold,
                "margin": margin,
                "geo": None if geo is None else {str(k): v for k, v in geo.items()},
                "geo_weight": geo_weight,
                "expected": [
                    {
                        "detection_taxon_id": ident.detection.taxon_id,
                        "detection_score": round(ident.detection.score, 7),
                        "confusion_set": list(ident.confusion_set),
                        "geo_applied": ident.geo_applied,
                        "absent": round(ident.absent, 7),
                        "raw_scores": {
                            str(k): round(v, 7) for k, v in sorted(ident.raw_scores.items())
                        },
                        "taxon_id": ident.result.taxon_id,
                        "rank": ident.result.rank,
                        "probability": round(ident.result.probability, 7),
                    }
                    for ident in found
                ],
            }
        )
    return {
        "spec_version": 1,
        "note": (
            "Generated by training/tools/gen_golden_audio.py. Kotlin's Audio must reproduce "
            "these exactly. See shared/taxonomy-spec.md §4A."
        ),
        "taxonomy": tax.to_dict(),
        "cases": cases,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="fail if the committed file is stale")
    args = ap.parse_args()

    fresh = json.dumps(build(), indent=2) + "\n"

    if args.check:
        if not OUT.exists():
            print(f"missing {OUT}", file=sys.stderr)
            return 1
        if OUT.read_text() != fresh:
            print(
                f"{OUT} is stale — the audio path's output changed.\n"
                "If that change was intended, run `python tools/gen_golden_audio.py` and "
                "commit the diff so the Kotlin side is updated in the same breath.",
                file=sys.stderr,
            )
            return 1
        print("golden audio fixture in sync")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(fresh)
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
