"""Where the checklist is weakest, so it can be checked without being read.

"Is there no way to just sanity check the list?" — and the answer is not to read 14,541 rows.
It is to sort them by how much the rule had to stretch to keep them, and read the top of that.

Every species on the list got there two ways at once: GBIF has five or more Danish observations
of it, and Denmark's national register vouches for it (§90). The second half has three clauses
and they are not equally strong:

- **by name** — the register carries this exact binomial. Nothing was inferred.
- **by synonym** — the register carries a name GBIF considers the same species. One step.
- **by genus only** — the register has never heard of this species, but it knows the genus. This
  is the clause that keeps *Corvus cornix*, and it is also the clause that would keep a garden
  escape in a genus with a wild Danish member.

So the audit is: everything held by genus alone, rarest first. That is a few hundred rows rather
than fourteen thousand, and it is where a wrong answer will be if there is one.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from .register import Register, binomial, genus_of

#: Weakest last — the order the report sorts by.
CLAUSES = ("name", "synonym", "genus")


@dataclass(frozen=True, slots=True)
class Row:
    key: int
    scientific_name: str
    vernacular_en: str | None
    family: str | None
    group: str
    records: int
    clause: str
    identifiable: bool
    has_photo: bool

    @property
    def weak(self) -> bool:
        return self.clause == "genus"


def clause_for(
    register: Register,
    scientific_name: str,
    synonyms: Sequence[str] = (),
) -> str | None:
    """Which clause vouched for this, or None if nothing did.

    Checked in the same order the filter checks them, so the report cannot disagree with the
    thing it is reporting on.
    """
    if binomial(scientific_name) in register.names:
        return "name"
    if genus_of(scientific_name) in register.genera:
        return "genus"
    if any(binomial(s) in register.names for s in synonyms):
        return "synonym"
    return None


def rows(
    checklist: Mapping[str, object],
    register: Register,
    groups: Mapping[int, str],
    synonyms: Mapping[int, Sequence[str]] | None = None,
    identifiable: frozenset[int] = frozenset(),
    photographed: frozenset[int] = frozenset(),
) -> list[Row]:
    species = checklist.get("species") or {}
    families = checklist.get("families") or {}
    synonyms = synonyms or {}
    out: list[Row] = []
    for key, entry in species.items():  # type: ignore[union-attr]
        taxon = int(key)
        family_id = entry.get("family")
        clause = clause_for(register, entry["name"], synonyms.get(taxon, ()))
        out.append(
            Row(
                key=taxon,
                scientific_name=entry["name"],
                vernacular_en=entry.get("vernacular_en"),
                family=(families.get(str(family_id)) or {}).get("name"),  # type: ignore[union-attr]
                group=groups.get(family_id, "Other") if family_id else "Other",
                records=int(entry.get("records") or 0),
                clause=clause or "unvouched",
                identifiable=taxon in identifiable,
                has_photo=taxon in photographed,
            )
        )
    return out


def weakest_first(rows_: Sequence[Row], limit: int | None = None) -> list[Row]:
    """Genus-only first, and within that the rarest — the two axes of doubt, together.

    A species held by its genus alone *and* seen five times is the most questionable row the
    list can produce. One held by name and seen forty thousand times is not worth your eyes.
    """
    # `CLAUSES` is strongest first, so reversing it makes the weakest sort first. The rank is
    # used as-is: negating it here put every by-name row at the top, which is the one thing
    # this page must not do, and the render showed it immediately.
    order = {clause: position for position, clause in enumerate(reversed(CLAUSES))}
    ranked = sorted(
        rows_,
        key=lambda row: (order.get(row.clause, -1), row.records, row.scientific_name),
    )
    return ranked[:limit] if limit else ranked
