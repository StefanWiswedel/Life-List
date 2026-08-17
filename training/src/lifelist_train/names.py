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

    def add(self, name: str, accepted_taxon_id: int) -> None:
        key = normalize(name)
        if not key:
            return

        existing = self._by_name.get(key)
        if existing is not None and existing != accepted_taxon_id:
            # A homonym across kingdoms, or a genuinely contested name. Refusing to
            # resolve it is correct; picking one silently is how a beetle ends up filed
            # as a plant.
            self._ambiguous.add(key)
            return
        self._by_name[key] = accepted_taxon_id

        parsed = parse(name)
        if parsed.binomial and parsed.infraspecific_epithet:
            # A subspecies also answers to its binomial, but only if nothing else claims it.
            binomial_key = normalize(parsed.binomial)
            if self._by_binomial.get(binomial_key, accepted_taxon_id) != accepted_taxon_id:
                self._ambiguous.add(binomial_key)
            else:
                self._by_binomial[binomial_key] = accepted_taxon_id

    def resolve(self, name: str) -> int | None:
        """Accepted taxon id for ``name``, or None if unknown or ambiguous.

        Tries exact normalised match first, then falls back to the binomial — so
        `Carabus granulatus subsp. interstitialis` still finds *Carabus granulatus*
        when the subspecies is not in our taxon list.
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
            return self._by_name.get(binomial_key) or self._by_binomial.get(binomial_key)
        return None

    def is_ambiguous(self, name: str) -> bool:
        return normalize(name) in self._ambiguous

    @property
    def ambiguous_names(self) -> tuple[str, ...]:
        """Names deliberately left unresolved. Report these; do not silently drop them."""
        return tuple(sorted(self._ambiguous))

    def __len__(self) -> int:
        return len(self._by_name)


def build_synonym_index(
    accepted: dict[int, str],
    synonyms: list[tuple[str, int]],
) -> SynonymIndex:
    """Build the index from accepted names and (synonym, accepted_id) pairs."""
    index = SynonymIndex()
    for taxon_id, name in accepted.items():
        index.add(name, taxon_id)
    for synonym, accepted_id in synonyms:
        index.add(synonym, accepted_id)
    return index
