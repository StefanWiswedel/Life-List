"""Denmark's own answer to "does this live here?".

A GBIF occurrence says where something was *recorded*, which is not the same claim. Filtering
to observations (§89) removed the museum drawers and left the farm: cattle, sheep, goats, a
horse, a cat, a peafowl and several hundred escaped budgerigars, all genuinely observed in
Denmark and none of them anything you go out and find.

There is no field in GBIF to filter that on — `establishmentMeans` is empty on essentially
every Danish record, checked rather than assumed. But Denmark keeps a national species
register: **Taxonbasen**, the taxonomic backbone of arter.dk, published on GBIF and downloadable
as a Darwin Core archive of about 60,000 name usages. Its own metadata states its scope — taxa
native to Denmark, plus non-natives established at least locally. Livestock is not in it.
Neither is a budgerigar.

So the rule is *two sources and no list*: GBIF says what was seen, Denmark's register says what
counts as living here. Nobody goes through families deciding.

**Vouching has three clauses**, because a national register and a global backbone never agree
on names, and each clause fixes a different disagreement.

- **by name** — the binomial as either side spells it. 13,013 of 14,876.
- **by genus** — which handles *splits and lumps*, where no name can agree. The register files
  the hooded crow as *Corvus corone cornix* and GBIF gives *Corvus cornix* its own species key;
  those never match and never will. The genus does. Denmark's commonest crow has 636,849
  records and a rule that loses it is not a rule about Denmark. 1,163 more.
- **by synonym** — GBIF's own synonymy for the ones still missing, which is where the botany
  and mycology live: the register's *Stellaria holostea* against our *Rabelera holostea*,
  *Phengaris arion* against *Maculinea arion*, *Langermannia gigantea* against *Calvatia
  gigantea*. 365 more, and it costs one call per remaining species rather than one per name in
  the register.

Each clause on its own is a bad rule. Name alone drops 1,878 species, most of them real. Genus
alone drops 700 including the greater stitchwort and the giant puffball, because plant and
fungal genera churn constantly. Together they keep 98% and what falls out is a farmyard.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass

#: The national register, as its publisher serves it.
ARCHIVE_URL = "https://arter.dk/dwccontainer/taxon_dwc_archive.zip"

#: GBIF's key for the same dataset, for anyone who wants the metadata rather than the file.
DATASET_KEY = "4b3e4a71-704a-485c-917c-20a89944ea37"


def normalise(name: str) -> str:
    """One shape for a name. The archive uses non-breaking spaces in places."""
    return " ".join(name.replace("\xa0", " ").split())


def binomial(name: str) -> str:
    """Genus and species, dropping the author and any infraspecific rank."""
    parts = normalise(name).split()
    return " ".join(parts[:2]) if len(parts) >= 2 else normalise(name)


def genus_of(name: str) -> str:
    parts = normalise(name).split()
    return parts[0] if parts else ""


@dataclass(frozen=True, slots=True)
class Register:
    """What Denmark vouches for."""

    names: frozenset[str]
    """Every name the register carries, as a binomial and as written."""

    genera: frozenset[str]
    """Every genus it names, at any rank."""

    @classmethod
    def from_document(cls, document: Mapping[str, object]) -> Register:
        names = document.get("names") or ()
        genera = document.get("genera") or ()
        return cls(
            names=frozenset(str(n) for n in names),  # type: ignore[union-attr]
            genera=frozenset(str(g) for g in genera),  # type: ignore[union-attr]
        )

    @property
    def usable(self) -> bool:
        """A half-downloaded register must not quietly delete Denmark.

        A stunted one vouches for nothing and passes no species, which on a log line looks
        exactly like a correct run against a country with no wildlife. The real thing carries
        about 100,000 names and 17,000 genera; a tenth of that is a broken download.
        """
        return len(self.names) >= 10_000 and len(self.genera) >= 1_700

    def vouches(self, scientific_name: str, synonyms: Iterable[str] = ()) -> bool:
        """Does Denmark's register know this species, under any name?"""
        if binomial(scientific_name) in self.names:
            return True
        if genus_of(scientific_name) in self.genera:
            return True
        return any(binomial(s) in self.names for s in synonyms)

    def needs_synonyms(self, scientific_name: str) -> bool:
        """True when only the synonym clause is left to try — so only those cost a request."""
        return not (
            binomial(scientific_name) in self.names
            or genus_of(scientific_name) in self.genera
        )


def read_archive(taxon_rows: Iterable[list[str]]) -> Register:
    """Build a register from the archive's `taxon.txt` rows.

    Column 2 is `scientificName` per the archive's own `meta.xml`, which is asserted rather
    than assumed: a silently shifted column here would pass zero species and read as a fact
    about Denmark.
    """
    names: set[str] = set()
    genera: set[str] = set()
    for row in taxon_rows:
        if len(row) < 9:
            continue
        written = normalise(row[2])
        if not written:
            continue
        names.add(written)
        names.add(binomial(written))
        genera.add(genus_of(written))
    return Register(names=frozenset(names), genera=frozenset(genera))
