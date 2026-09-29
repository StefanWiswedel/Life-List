"""Narrowing a determination the model could not finish — spec §5.

    "I tried to ID a wasp today and couldn't. A feature where it identifies to family or
     genus and then asks for defining features (are the antenna yellow or black) to help
     ID further would be amazing but I imagine very difficult."

Less difficult than it sounds, because **the hard half already exists**: the rollup stops at
genus precisely when it was choosing between a known, small, taxonomically coherent set of
species (spec §3.3, §4A.2). The app already knows *Vespula vulgaris* from *V. germanica* was
the question. What it lacks is the answer, and the answer is one sentence.

There are two ways to get that sentence, and which one to offer is decided by whether the
evidence is still in front of you:

- **The photograph you have not taken.** For *Vespula* the character is the clypeus — the
  face. A person standing next to the wasp should be told to photograph the face, not
  interrogated about it, because the app already fuses several photographs of one individual
  (spec §3.2) and a picture is evidence while a memory is a guess wearing evidence's clothes.
- **The question, for when that is impossible.** An old photograph from the camera roll, an
  animal long gone, a specimen photographed once. Then asking is all there is, and the answer
  is worth having even though it is weaker.

**A character is a likelihood, never a mask.** This is the same rule the geo prior earned
(§4A.4) and it matters more here, not less. Wikipedia's own account of the *Vespula* clypeus
says the mark "can sometimes appear broken... making it look extremely similar" — so a person
answering honestly can still be describing *vulgaris*. Eliminating it outright would make the
atypical individual unloggable, and the atypical individual is the one worth logging. So each
option carries `reliability`: how often a species showing that state is seen that way. The
update multiplies; it never zeroes.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field

import numpy as np

#: How sure a well-behaved character is. Not 1.0: a character that cannot be wrong does not
#: exist in the field, and a 1.0 would make one honest answer delete a species permanently.
DEFAULT_RELIABILITY = 0.92

#: What a species that does *not* show the chosen state gets multiplied by. Not zero, for the
#: same reason. Small enough that two independent characters settle almost anything.
CONTRARY = 0.06


@dataclass(frozen=True, slots=True)
class Option:
    """One answer a person can give, and what it implies."""

    label: str
    """What the person sees: "three small black dots"."""

    taxa: tuple[int, ...]
    """The species that show this state."""

    reliability: float = DEFAULT_RELIABILITY
    """How often one of `taxa` actually looks like this. Below the default where the source
    says the character is variable — which is information a hand-written key throws away."""


@dataclass(frozen=True, slots=True)
class Character:
    """One question, its answers, and where the claim came from."""

    key: str
    prompt: str
    """"What does the face look like?" — not "is the clypeus anchor-shaped?". The person is
    holding a phone, not a monograph."""

    options: tuple[Option, ...]
    source: str = ""
    """A URL. Every claim here is quoted from somewhere, because a character I wrote myself is
    an error nobody can trace and this app's one unforgivable output is a confident wrong
    answer. See VERIFICATION.md §95."""

    note: str = ""
    """The caveat, where the source gives one. Shown, not swallowed."""

    @property
    def taxa(self) -> frozenset[int]:
        return frozenset(t for option in self.options for t in option.taxa)


@dataclass(frozen=True, slots=True)
class Guide:
    """Everything known about telling one genus apart."""

    taxon_id: int
    """The genus (or family) this narrows within."""

    view: str = ""
    """The photograph that would settle it: "the face", "the underside of the hindwing".
    Offered first whenever the animal might still be there."""

    characters: tuple[Character, ...] = field(default_factory=tuple)

    def applicable(self, candidates: Sequence[int]) -> tuple[Character, ...]:
        """Characters that would actually split *these* candidates.

        A question whose options all point at species still in play, or all at species
        already out, is a question that cannot change the answer — and asking it spends the
        one thing this interaction has, which is the person's patience.
        """
        live = set(candidates)
        out = []
        for character in self.characters:
            split = [o for o in character.options if live & set(o.taxa)]
            if len(split) >= 2:
                out.append(character)
        return tuple(out)


def apply_answer(
    probabilities: Mapping[int, float],
    character: Character,
    chosen: str,
) -> dict[int, float]:
    """Fold one answer into the candidate probabilities. A likelihood, never a mask.

    Species the chosen option names are multiplied by its reliability; species it does not are
    multiplied by `CONTRARY`. Species the character says nothing about — a candidate outside
    its `taxa` — are left exactly alone, because a character that does not mention a species
    is not evidence against it. That last clause is the one that is easy to get wrong and it
    is why this is a function with tests rather than three lines inside a screen.
    """
    option = next((o for o in character.options if o.label == chosen), None)
    if option is None:
        raise KeyError(f"{chosen!r} is not an option of {character.key!r}")
    covered = character.taxa
    named = set(option.taxa)
    out: dict[int, float] = {}
    for taxon, p in probabilities.items():
        if taxon not in covered:
            out[taxon] = float(p)
        elif taxon in named:
            out[taxon] = float(p) * option.reliability
        else:
            out[taxon] = float(p) * CONTRARY
    return out


def renormalise(probabilities: Mapping[int, float]) -> dict[int, float]:
    """Back to a distribution, or unchanged when an answer has left nothing at all.

    Every candidate at zero would divide by zero, and the honest result of that is "your
    answer rules out everything I was considering" — which is a real outcome, means the
    determination was wrong further up, and must not be a crash.
    """
    total = float(sum(probabilities.values()))
    if total <= 0.0:
        return {t: float(p) for t, p in probabilities.items()}
    return {t: float(p) / total for t, p in probabilities.items()}


def narrow(
    leaf_probabilities: np.ndarray,
    index_of: Mapping[int, int],
    character: Character,
    chosen: str,
) -> np.ndarray:
    """The same update against the dense leaf vector the rollup consumes.

    Kept as a separate function from `apply_answer` rather than a conversion, because the
    vision path holds a dense array over leaf indices and turning it into a dict and back for
    every answer is how a rounding difference gets into the golden fixture.
    """
    out = np.asarray(leaf_probabilities, dtype=np.float64).copy()
    option = next((o for o in character.options if o.label == chosen), None)
    if option is None:
        raise KeyError(f"{chosen!r} is not an option of {character.key!r}")
    named = set(option.taxa)
    for taxon in character.taxa:
        position = index_of.get(taxon)
        if position is None:
            continue
        out[position] *= option.reliability if taxon in named else CONTRARY
    total = out.sum()
    return (out / total if total > 0 else np.asarray(leaf_probabilities, dtype=np.float64))
