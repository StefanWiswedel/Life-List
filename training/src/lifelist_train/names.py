"""Scientific-name normalisation and synonym resolution.

Every downstream join — GBIF backbone to iNaturalist taxa, BirdNET labels to GBIF keys,
user search to the life list — goes through here. Getting it wrong does not raise; it
silently drops taxa, which then look like gaps in the data rather than a bug in the code.

Pure string and dict work, no network. The GBIF API client lives in `gbif.py`.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

# Infraspecific and connecting terms that carry no nomenclatural weight for matching.
_RANK_MARKERS = {
    "subsp", "ssp", "var", "subvar", "f", "form", "forma", "cv", "nothosubsp", "nothovar",
}

# Author citations, sensu-lato qualifiers and uncertainty markers to strip before matching.
_QUALIFIERS = {
    "sensu", "s", "l", "str", "auct", "non", "nec", "emend", "sec",
    "cf", "aff", "sp", "spp", "indet", "incertae", "sedis",
}

_AGGREGATE_MARKERS = {"agg", "aggr", "aggregate", "complex", "group", "gr"}

_WS = re.compile(r"\s+")
_PARENTHETICAL = re.compile(r"\([^)]*\)")
_NON_NAME = re.compile(r"[^a-z\s]")

# A hybrid sign is the multiplication sign, or a lone `x`/`X`/`+` used as a separator —
# never the letter x inside a word, so `Buxus` and `Rumex` stay intact.
_HYBRID_SPLIT = re.compile(r"\s*(?:×|\bx\b|\bX\b|\+)\s*")


def _is_hybrid_formula(name: str) -> bool:
    return bool(_HYBRID_SPLIT.search(name))


@dataclass(frozen=True, slots=True)
class ParsedName:
    """A scientific name decomposed far enough to match against another one."""

    normalized: str
    genus: str | None
    specific_epithet: str | None
    infraspecific_epithet: str | None
    is_hybrid: bool
    is_aggregate: bool

    @property
    def binomial(self) -> str | None:
        if self.genus and self.specific_epithet:
            return f"{self.genus} {self.specific_epithet}"
        return None


def normalize(name: str) -> str:
    """Aggressively normalise a scientific name for equality comparison.

    Deliberately lossy — the output is a match key, never something to display.

    The order of operations matters. Author citations are stripped **before**
    lowercasing, because capitalisation is the only thing distinguishing an author
    surname from an infraspecific epithet: `Carabus granulatus Linnaeus` and
    `Carabus granulatus interstitialis` are structurally identical once lowercased.
    Botanical abbreviations like `L.` would be caught either way, but `Linnaeus`
    would not — and an index where the abbreviated form matches and the spelled-out
    form does not is worse than one where neither does, because it fails on a subset
    of records and looks like patchy data.
    """
    if not name:
        return ""

    # Hybrid formulae get both parents normalised independently and rejoined with an
    # explicit marker, so `Anser anser × Branta canadensis` can never collide with
    # either parent. Flattening it would let a hybrid resolve to one parent — a false
    # identification dressed as a match. Arter carries records like Grågås × canadagås,
    # so this is a live case in Danish data, not a theoretical one.
    if _is_hybrid_formula(name):
        parents = [p for p in _HYBRID_SPLIT.split(name) if p.strip()]
        if len(parents) > 1:
            normalised_parents = [normalize(p) for p in parents]
            if all(normalised_parents):
                return " x ".join(normalised_parents)

    text = unicodedata.normalize("NFKD", name)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.replace("×", " ").replace("+", " ")
    text = _PARENTHETICAL.sub(" ", text)
    text = text.replace(".", " ").replace(",", " ")

    raw_tokens = [t for t in _WS.split(text) if t]
    if not raw_tokens:
        return ""

    # SHOUTED input carries no capitalisation signal, so the author heuristic would
    # strip the whole name. Detect and skip it rather than returning the genus alone.
    alpha = [t for t in raw_tokens if t.isalpha()]
    shouting = bool(alpha) and all(t.isupper() for t in alpha)

    kept: list[str] = []
    for position, token in enumerate(raw_tokens):
        if token.isdigit():  # publication year
            continue
        lowered = _NON_NAME.sub("", token.lower())
        if not lowered:
            continue
        if lowered in _RANK_MARKERS or lowered in _QUALIFIERS or lowered in _AGGREGATE_MARKERS:
            continue
        # Past the genus, a capitalised token is an author, not an epithet.
        if position > 0 and not shouting and token[0].isupper():
            continue
        kept.append(lowered)

    return " ".join(kept[:3])


def parse(name: str) -> ParsedName:
    """Decompose a scientific name into its nomenclatural parts."""
    raw = name or ""
    is_hybrid = _is_hybrid_formula(raw)
    lowered = f" {raw.lower()} ".replace(".", " ")
    is_aggregate = any(f" {marker} " in lowered for marker in _AGGREGATE_MARKERS)

    normalized = normalize(raw)
    parts = normalized.split()

    return ParsedName(
        normalized=normalized,
        genus=parts[0].capitalize() if parts else None,
        specific_epithet=parts[1] if len(parts) > 1 else None,
        infraspecific_epithet=parts[2] if len(parts) > 2 else None,
        is_hybrid=is_hybrid,
        is_aggregate=is_aggregate,
    )


@dataclass
class SynonymIndex:
    """Maps any name — accepted or synonym — to an accepted taxon id.

    Built once from the GBIF backbone and persisted, because the app needs it too: a
    naturalist searching a name they learned twenty years ago should still find the
    record (build plan §3 stage 1).
    """

    _by_name: dict[str, int] = field(default_factory=dict)
    _by_binomial: dict[str, int] = field(default_factory=dict)
    _ambiguous: set[str] = field(default_factory=set)
    """True homonyms: one spelling, two different accepted taxa. Refuse these."""

    _weak: set[str] = field(default_factory=set)
    """Keys currently held by a weak claim — a synonym, or a DOUBTFUL backbone entry."""

    _ambiguous_binomial: set[str] = field(default_factory=set)
    """Binomials reached only by *fallback* from several trinomials that disagree.

    Kept apart from `_ambiguous` deliberately. `Anas crecca carolinensis` is accepted
    under a different key than `Anas crecca crecca`, which makes the binomial an unsafe
    *guess* from a trinomial — but it says nothing about `Anas crecca` itself, which is
    an accepted name with exactly one key. Merging the two sets made the index refuse
    347 unambiguous Danish species, including teal, bullfinch, common frog and
    chanterelle, and cost 17% of the training photos."""

    def add(self, name: str, accepted_taxon_id: int, weak: bool = False) -> None:
        """Register a name.

        ``weak`` marks a claim that should lose a collision rather than cause one: a
        synonym, or an entry GBIF itself labels DOUBTFUL. Strong beats weak, weak never
        displaces strong, and only two claims of *equal* standing make a name ambiguous.

        This is not a softening of "never guess a taxon". Choosing an ACCEPTED entry
        over a DOUBTFUL one of the same name is not a guess — it is reading the status
        field GBIF publishes for exactly this purpose. Measured on the Danish list, 134
        names carry more than one accepted entry; 133 of those pairs sit in the same
        family, and 89 are an ACCEPTED/DOUBTFUL pair where the doubtful entry has two
        orders of magnitude fewer occurrences. Exactly one is a real cross-kingdom
        homonym, and that one still refuses.
        """
        key = normalize(name)
        if not key:
            return

        existing = self._by_name.get(key)
        if existing is not None and existing != accepted_taxon_id:
            existing_weak = key in self._weak
            if existing_weak and not weak:
                pass  # the strong claim takes it over
            elif weak and not existing_weak:
                return  # a weak claim never displaces a strong one
            else:
                # Equal standing: a homonym across kingdoms, or a genuinely contested
                # name. Refusing is correct; picking one silently is how a beetle ends
                # up filed as a plant.
                self._ambiguous.add(key)
                return

        self._by_name[key] = accepted_taxon_id
        if weak:
            self._weak.add(key)
        else:
            self._weak.discard(key)

        parsed = parse(name)
        if parsed.binomial and parsed.infraspecific_epithet:
            # A subspecies also answers to its binomial, but only if nothing else claims it.
            binomial_key = normalize(parsed.binomial)
            if self._by_binomial.get(binomial_key, accepted_taxon_id) != accepted_taxon_id:
                self._ambiguous_binomial.add(binomial_key)
            else:
                self._by_binomial[binomial_key] = accepted_taxon_id

    def resolve(self, name: str) -> int | None:
        """Accepted taxon id for ``name``, or None if unknown or ambiguous.

        Precedence matters, and getting it wrong is expensive. An **exact accepted
        name always wins**: that a dozen subspecies of *Rana temporaria* disagree about
        which key their binomial should fall back to is a fact about the fallback, not
        about *Rana temporaria*, which has exactly one accepted key. Only a genuine
        homonym — one spelling claimed by two different accepted taxa — refuses.

        Order: exact name, then the binomial as an accepted name, then the binomial by
        fallback. So `Carabus granulatus subsp. interstitialis` still finds *Carabus
        granulatus* when the subspecies is not in our taxon list.
        """
        key = normalize(name)
        if not key or key in self._ambiguous:
            return None
        if key in self._by_name:
            return self._by_name[key]

        parsed = parse(name)

        # The binomial fallback must not apply to hybrid formulae. `Anser anser ×
        # Branta canadensis` parses to a leading binomial of `Anser anser`, and
        # falling back to it would resolve a hybrid to one of its parents — the
        # precise false identification the hybrid-aware key exists to prevent.
        if parsed.is_hybrid:
            return None

        if parsed.binomial:
            binomial_key = normalize(parsed.binomial)
            if binomial_key in self._ambiguous:
                return None
            if binomial_key in self._by_name:
                return self._by_name[binomial_key]
            if binomial_key in self._ambiguous_binomial:
                return None
            return self._by_binomial.get(binomial_key)
        return None

    def is_ambiguous(self, name: str) -> bool:
        """True when the index refuses this name *because* it is ambiguous.

        Mirrors `resolve` rather than inspecting one set, so a caller that checks this
        before resolving gets the same verdict. A name with an exact accepted entry is
        never ambiguous, however contested its subspecies are.
        """
        key = normalize(name)
        if not key:
            return False
        if key in self._ambiguous:
            return True
        if key in self._by_name:
            return False

        parsed = parse(name)
        if parsed.is_hybrid or not parsed.binomial:
            return False
        binomial_key = normalize(parsed.binomial)
        if binomial_key in self._ambiguous:
            return True
        if binomial_key in self._by_name:
            return False
        return binomial_key in self._ambiguous_binomial

    @property
    def ambiguous_names(self) -> tuple[str, ...]:
        """True homonyms, deliberately unresolved. Report these; never silently drop."""
        return tuple(sorted(self._ambiguous))

    @property
    def ambiguous_binomials(self) -> tuple[str, ...]:
        """Binomials unsafe to reach *by fallback*, though possibly fine by exact name."""
        return tuple(sorted(self._ambiguous_binomial))

    def __len__(self) -> int:
        return len(self._by_name)


def build_synonym_index(
    accepted: dict[int, str],
    synonyms: list[tuple[str, int]],
    doubtful_ids: frozenset[int] = frozenset(),
) -> SynonymIndex:
    """Build the index from accepted names and (synonym, accepted_id) pairs.

    ``doubtful_ids`` are taxon keys GBIF marks DOUBTFUL. They are added as weak claims,
    so a DOUBTFUL duplicate of an ACCEPTED name loses instead of poisoning it. Synonyms
    are weak for the same reason: an accepted name outranks a synonym that happens to
    collide with it.

    Strong entries go in first, so a weak claim never briefly owns a key that a strong
    one is about to want.
    """
    index = SynonymIndex()
    for taxon_id, name in accepted.items():
        if taxon_id not in doubtful_ids:
            index.add(name, taxon_id)
    for taxon_id, name in accepted.items():
        if taxon_id in doubtful_ids:
            index.add(name, taxon_id, weak=True)
    for synonym, accepted_id in synonyms:
        index.add(synonym, accepted_id, weak=True)
    return index
