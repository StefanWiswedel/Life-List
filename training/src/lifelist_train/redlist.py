"""The Danish Red List: how threatened a species is, and how many of its family Denmark has.

Two different things arrive in one artefact because they arrive from one place. `Den danske
Rødliste` assesses 13,899 Danish species, and each assessment carries a category, a family and
an order — so the same download answers "is this one in trouble?" and "how many *are* there?".

**The second is the reason this exists.** A life list wants to say "12 of 310 Geometridae", and
until now the only denominator available was our own vocabulary, which measures the model rather
than Denmark and shifts every time we retrain. The Red List's family totals are a fact about the
country.

The category is the lesser half and worth being honest about: of the 2,538 species in the model
it covers, 1,941 are Least Concern. As a badge on the 128 that are threatened it means
something. As a rarity scale it is flat, which is why rarity is not what it is used for.

Free to use with attribution, per the API's terms, and there is no key. IUCN's global list was
the obvious alternative and is not usable here: it forbids redistribution without written
permission, and bundling categories into an offline app is redistribution.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any

CITATION = (
    "Moeslund, J.E. et al. Den danske Rødliste. Aarhus Universitet, DCE — Nationalt Center "
    "for Miljø og Energi. https://ecos.au.dk/redlist"
)
API = "https://api.redlist.au.dk/public/v1/summary/assessments/"

#: Categories worth putting on screen. `LC` is three quarters of the list and says nothing a
#: person wants to read; `NA`, `NE` and `DD` are statements about the assessment, not the animal.
NOTABLE = ("RE", "CR", "EN", "VU", "NT")

#: What each category means, in the words the app will use. Danish list, English gloss.
MEANING = {
    "RE": "Regionally extinct in Denmark",
    "CR": "Critically endangered in Denmark",
    "EN": "Endangered in Denmark",
    "VU": "Vulnerable in Denmark",
    "NT": "Near threatened in Denmark",
    "LC": "Least concern in Denmark",
    "DD": "Too little known to assess",
    "NA": "Not assessed for Denmark",
    "NE": "Not evaluated",
}


@dataclass(frozen=True, slots=True)
class MatchReport:
    assessed: int
    leaves: int
    matched: int
    notable: int
    names_gained: int

    def summary(self) -> str:
        rate = self.matched / self.leaves if self.leaves else 0.0
        return (
            f"{self.assessed} assessments; matched {self.matched}/{self.leaves} leaves "
            f"({rate:.1%}), {self.notable} worth a badge, {self.names_gained} Danish names gained"
        )


def normalise(name: str) -> str:
    """A scientific name as a matching key.

    Case and stray whitespace only. Deliberately not clever: the Red List publishes bare
    binomials and so does the taxonomy, and a fuzzy match between two lists of species names is
    how a beetle gets filed as a plant.
    """
    return " ".join(str(name).split()).lower()


def family_totals(assessments: Iterable[Mapping[str, Any]]) -> dict[str, int]:
    """Species per family in Denmark, as the Red List has them.

    Counted over every assessment rather than only the ones in the model — that is the whole
    point. `Geometridae` comes back 310 where the model knows 147.
    """
    totals: dict[str, int] = {}
    for assessment in assessments:
        family = assessment.get("family")
        if family:
            totals[str(family)] = totals.get(str(family), 0) + 1
    return totals


def match(
    assessments: Sequence[Mapping[str, Any]],
    leaves: Sequence[Mapping[str, Any]],
) -> tuple[dict[int, str], dict[int, str], MatchReport]:
    """Categories and Danish names for the leaves the Red List covers, keyed by GBIF id.

    A Danish name is taken **only where the taxonomy has none**. GBIF's vernaculars and the Red
    List's disagree in places, and quietly replacing one authority's name with another's inside
    a taxonomy that says it came from GBIF would be a small lie that is hard to find later.
    """
    by_name = {normalise(a["scientificName"]): a for a in assessments if a.get("scientificName")}

    categories: dict[int, str] = {}
    danish: dict[int, str] = {}
    for leaf in leaves:
        found = by_name.get(normalise(leaf["scientific_name"]))
        if found is None:
            continue
        taxon_id = int(leaf["taxon_id"])
        category = str(found.get("redlistCategory") or "").upper()
        if category:
            categories[taxon_id] = category
        name = found.get("vernacularName")
        if name and not leaf.get("vernacular_da"):
            danish[taxon_id] = str(name)

    return (
        categories,
        danish,
        MatchReport(
            assessed=len(assessments),
            leaves=len(leaves),
            matched=len(categories),
            notable=sum(1 for c in categories.values() if c in NOTABLE),
            names_gained=len(danish),
        ),
    )


def document(
    categories: Mapping[int, str],
    danish: Mapping[int, str],
    totals: Mapping[str, int],
    fetched: str,
) -> dict[str, Any]:
    """The shipped artefact. Sorted, so a rebuild shows a readable diff."""
    return {
        "source": "Den danske Rødliste",
        "citation": CITATION,
        "url": "https://ecos.au.dk/redlist",
        "fetched": fetched,
        "categories": {str(k): v for k, v in sorted(categories.items())},
        "vernacular_da": {str(k): v for k, v in sorted(danish.items())},
        "family_totals": dict(sorted(totals.items())),
    }
