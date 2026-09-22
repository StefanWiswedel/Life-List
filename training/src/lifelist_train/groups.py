"""The groups a life list is broken into.

These are GBIF keys, and they are also compiled into `core/.../LifeList.kt` as
`DEFAULT_GROUPS`. Two copies of a list of magic numbers is exactly the kind of thing that
drifts silently — `tests/test_groups.py` reads the Kotlin source and asserts they still agree,
the same trick `tools/gen_golden.py --check` plays on the rollup.

Why they exist on the Python side at all: "how good is it on moths?" is the question that keeps
coming back from real use, and a single headline accuracy cannot answer it. A model at 94.7%
rollup accuracy can be 97% on birds and 70% on micro-moths, and only one of those numbers
describes the evening someone actually had.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence

#: Order matters: the first ancestor found wins, so Insecta is tested before Animalia would be,
#: and Fish is last among the chordates because it is defined as the ones left over.
#:
#: **[corrected 22 Sep 2026]** Reptiles was 358 (Reptilia) and Fish was 204 (Actinopterygii),
#: and neither key appears in GBIF's current backbone lineages. Every Danish reptile and every
#: fish the model knows was landing in "Other" — a grass snake sits under class *Squamata*, and
#: a pike has no class at all, its order hanging straight off Chordata. The home screen has
#: been offering "nothing yet in fish, reptiles" as categories that could never fill.
#:
#: So Fish is not a key any more, it is `Chordata`, placed after the four vertebrate groups
#: that are tested before it. That survives GBIF reshuffling its fish classes again, which it
#: has now done twice. The cost is fifteen species of sea squirt filed as fish; the alternative
#: was a dozen order keys that break on the next revision.
#:
#: Reptiles keeps *both* keys. Two entries with one label are fine — the first match wins and
#: both answer to the same name — and dropping 358 would have made the app right about GBIF
#: today and wrong about every tree that still uses Reptilia, which is how this went wrong in
#: the first place.
DEFAULT_GROUPS: tuple[tuple[str, int], ...] = (
    ("Birds", 212),
    ("Mammals", 359),
    ("Reptiles", 11592253),
    ("Reptiles", 358),
    ("Amphibians", 131),
    ("Fish", 44),
    ("Insects", 216),
    ("Arachnids", 367),
    ("Crustaceans", 229),
    ("Molluscs", 52),
    ("Worms", 42),
    ("Plants", 6),
    ("Fungi", 5),
)

UNGROUPED = "Other"

#: Finer than the groups above, for the question that actually gets asked. A moth is an insect,
#: and being told the model is 88% on "insects" says nothing about whether it can tell two
#: geometers apart. These are GBIF order and family keys.
LEPIDOPTERA = 797
FINE_GROUPS: tuple[tuple[str, int], ...] = (
    ("Moths & butterflies", LEPIDOPTERA),
    ("Beetles", 1470),
    ("Flies", 811),
    ("Bees, wasps & ants", 1457),
    ("True bugs", 809),
    ("Dragonflies", 789),
)


def group_of(
    lineage: Sequence[int],
    groups: Iterable[tuple[str, int]] = DEFAULT_GROUPS,
    fallback: str = UNGROUPED,
) -> str:
    """Which group a taxon belongs to, given its root-first lineage.

    Mirrors `LifeList.groupOf`. Takes a lineage rather than a taxonomy so it can be tested
    without building a tree, and so the caller decides how lineages are obtained.
    """
    seen = set(lineage)
    for label, taxon_id in groups:
        if taxon_id in seen:
            return label
    return fallback
