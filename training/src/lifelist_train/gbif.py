"""GBIF backbone and occurrence handling — build plan §3, stage 1.

Network access is confined to `GbifClient`. Everything that decides anything is a pure
function over already-fetched records, so the logic is testable without a network — which
matters, because the pipeline runs on Colab and the logic gets written somewhere else.

GBIF API reference: https://techdocs.gbif.org/en/openapi/
"""

from __future__ import annotations

import time
from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field
from typing import Any

from .names import SynonymIndex
from .taxonomy import RANK_ORDER, ROOT_ID, Taxon

GBIF_API = "https://api.gbif.org/v1"

# GBIF rank strings are uppercase; ours are lowercase and a subset.
_RANK_MAP = {
    "KINGDOM": "kingdom",
    "PHYLUM": "phylum",
    "CLASS": "class",
    "ORDER": "order",
    "FAMILY": "family",
    "GENUS": "genus",
    "SPECIES": "species",
    "SUBSPECIES": "subspecies",
    "VARIETY": "subspecies",
    "FORM": "subspecies",
}

LINEAGE_FIELDS = (
    ("kingdom", "kingdomKey"),
    ("phylum", "phylumKey"),
    ("class", "classKey"),
    ("order", "orderKey"),
    ("family", "familyKey"),
    ("genus", "genusKey"),
)


@dataclass(frozen=True, slots=True)
class GbifTaxon:
    """One accepted taxon as returned by the GBIF backbone."""

    key: int
    scientific_name: str
    rank: str
    status: str
    lineage: dict[str, int] = field(default_factory=dict)
    lineage_names: dict[str, str] = field(default_factory=dict)
    vernacular_en: str | None = None
    vernacular_da: str | None = None

    @property
    def is_accepted(self) -> bool:
        return self.status.upper() in {"ACCEPTED", "DOUBTFUL"}


def parse_rank(gbif_rank: str | None) -> str | None:
    """Map a GBIF rank string to ours, or None if we do not model it."""
    if not gbif_rank:
        return None
    return _RANK_MAP.get(gbif_rank.upper())


def parse_backbone_record(record: dict[str, Any]) -> GbifTaxon | None:
    """Parse one GBIF species record. Returns None for records we cannot use."""
    key = record.get("nubKey") or record.get("key") or record.get("usageKey")
    rank = parse_rank(record.get("rank"))
    name = record.get("canonicalName") or record.get("scientificName")
    if key is None or rank is None or not name:
        return None

    lineage: dict[str, int] = {}
    lineage_names: dict[str, str] = {}
    for rank_name, key_field in LINEAGE_FIELDS:
        value = record.get(key_field)
        if value is not None:
            lineage[rank_name] = int(value)
            named = record.get(rank_name)
            if named:
                lineage_names[rank_name] = named

    return GbifTaxon(
        key=int(key),
        scientific_name=name,
        rank=rank,
        status=str(record.get("taxonomicStatus") or "ACCEPTED"),
        lineage=lineage,
        lineage_names=lineage_names,
    )


def build_taxonomy_nodes(taxa: Iterable[GbifTaxon]) -> list[Taxon]:
    """Assemble a `Taxonomy`-ready node list from accepted leaf taxa and their lineages.

    Internal nodes are materialised from the lineage keys that GBIF attaches to every
    record, so no extra requests are needed. Ranks absent from a lineage are simply
    skipped — real taxonomies have gaps and inventing nodes to fill them would be a
    fabrication (spec §1.1, invariant 5).
    """
    leaves = [t for t in taxa if t.is_accepted]

    nodes: dict[int, Taxon] = {
        ROOT_ID: Taxon(taxon_id=ROOT_ID, parent_id=None, rank="root", scientific_name="Life")
    }

    for taxon in leaves:
        # Only the lineage ranks strictly shallower than this taxon's own rank.
        chain = [
            (rank_name, taxon.lineage[rank_name])
            for rank_name, _ in LINEAGE_FIELDS
            if rank_name in taxon.lineage
            and RANK_ORDER[rank_name] < RANK_ORDER[taxon.rank]
            and taxon.lineage[rank_name] != taxon.key
        ]

        parent_id = ROOT_ID
        for rank_name, key in chain:
            if key not in nodes:
                nodes[key] = Taxon(
                    taxon_id=key,
                    parent_id=parent_id,
                    rank=rank_name,
                    scientific_name=taxon.lineage_names.get(rank_name, str(key)),
                )
            parent_id = key

        if taxon.key in nodes:
            continue
        nodes[taxon.key] = Taxon(
            taxon_id=taxon.key,
            parent_id=parent_id,
            rank=taxon.rank,
            scientific_name=taxon.scientific_name,
            vernacular_en=taxon.vernacular_en,
            vernacular_da=taxon.vernacular_da,
        )

    return assign_leaf_indices(list(nodes.values()))


def assign_leaf_indices(nodes: list[Taxon]) -> list[Taxon]:
    """Assign contiguous leaf indices to childless nodes (spec §1.1, invariants 3 and 4).

    Ordered by ascending taxon_id so the head's output dimension is stable across runs.
    A reordering here silently invalidates every exported model, so it is deliberate and
    deterministic rather than incidental.
    """
    has_children = {node.parent_id for node in nodes if node.parent_id is not None}
    out: list[Taxon] = []
    next_index = 0
    for node in sorted(nodes, key=lambda n: n.taxon_id):
        if node.taxon_id in has_children or node.parent_id is None:
            out.append(Taxon(**{**_as_dict(node), "leaf_index": None}))
        else:
            out.append(Taxon(**{**_as_dict(node), "leaf_index": next_index}))
            next_index += 1
    return out


def _as_dict(node: Taxon) -> dict[str, Any]:
    return {
        "taxon_id": node.taxon_id,
        "parent_id": node.parent_id,
        "rank": node.rank,
        "scientific_name": node.scientific_name,
        "vernacular_da": node.vernacular_da,
        "vernacular_en": node.vernacular_en,
    }


def collect_synonyms(records: Iterable[dict[str, Any]]) -> list[tuple[str, int]]:
    """Extract (synonym name, accepted key) pairs from GBIF synonym records."""
    out: list[tuple[str, int]] = []
    for record in records:
        status = str(record.get("taxonomicStatus") or "").upper()
        if "SYNONYM" not in status:
            continue
        name = record.get("canonicalName") or record.get("scientificName")
        accepted = record.get("acceptedKey") or record.get("acceptedUsageKey")
        if name and accepted is not None:
            out.append((str(name), int(accepted)))
    return out


def build_index(taxa: Iterable[GbifTaxon], synonyms: Iterable[tuple[str, int]]) -> SynonymIndex:
    index = SynonymIndex()
    for taxon in taxa:
        index.add(taxon.scientific_name, taxon.key)
    for name, accepted in synonyms:
        index.add(name, accepted)
    return index


class GbifClient:
    """Thin GBIF REST client. The only thing here that touches the network.

    Mostly not exercised in unit tests by design — mocking an HTTP client mostly tests
    the mock, and the parsing above is what carries the logic. What *is* tested is that
    an injected session is the only thing this class talks to, because that injection
    point is what makes the concurrent fetch in `cli/taxa.py` testable without a network.
    """

    def __init__(
        self,
        base_url: str = GBIF_API,
        pause_s: float = 0.1,
        session: Any = None,
        pool_size: int = 16,
    ):
        self.base_url = base_url.rstrip("/")
        self.pause_s = pause_s
        self._session = session
        self._pool_size = pool_size

    def _ensure_session(self) -> Any:
        """A pooled, connection-reusing session, created on first use.

        Calling `requests.get` directly opens and TLS-handshakes a fresh connection per
        request. Stage 1 makes three requests per taxon over tens of thousands of taxa,
        so the handshake dominated the run: measured against GBIF, per-taxon time was
        ~9 s without keep-alive. The pool is sized for the worker count in
        `cli/taxa.py`, since a smaller pool silently serialises the workers.
        """
        if self._session is None:
            import requests
            from requests.adapters import HTTPAdapter

            session = requests.Session()
            adapter = HTTPAdapter(pool_connections=self._pool_size, pool_maxsize=self._pool_size)
            session.mount("https://", adapter)
            session.mount("http://", adapter)
            self._session = session
        return self._session

    def _get(self, path: str, **params: Any) -> dict[str, Any]:
        session = self._ensure_session()
        response = session.get(f"{self.base_url}{path}", params=params, timeout=60)
        response.raise_for_status()
        time.sleep(self.pause_s)  # be a good citizen against a free public API
        return response.json()

    def occurrence_species_keys(
        self,
        country: str = "DK",
        limit: int = 1000,
        max_pages: int | None = None,
    ) -> Iterator[tuple[int, int]]:
        """Yield (species key, occurrence count) for a country, commonest first."""
        offset = 0
        pages = 0
        while True:
            payload = self._get(
                "/occurrence/search",
                country=country,
                facet="speciesKey",
                facetLimit=limit,
                facetOffset=offset,
                limit=0,
            )
            facets = payload.get("facets") or []
            counts = next(
                (f.get("counts", []) for f in facets if f.get("field") == "SPECIES_KEY"),
                [],
            )
            if not counts:
                return
            for entry in counts:
                try:
                    yield int(entry["name"]), int(entry["count"])
                except (KeyError, TypeError, ValueError):
                    continue
            offset += limit
            pages += 1
            if max_pages is not None and pages >= max_pages:
                return

    def species(self, key: int) -> dict[str, Any]:
        return self._get(f"/species/{key}")

    def synonyms(self, key: int, limit: int = 100) -> list[dict[str, Any]]:
        payload = self._get(f"/species/{key}/synonyms", limit=limit)
        return list(payload.get("results") or [])

    def vernacular_names(self, key: int, limit: int = 100) -> list[dict[str, Any]]:
        payload = self._get(f"/species/{key}/vernacularNames", limit=limit)
        return list(payload.get("results") or [])


def pick_vernacular(records: Iterable[dict[str, Any]], language: str) -> str | None:
    """Choose one vernacular name for a language.

    GBIF returns many, of wildly varying quality. Prefer a record flagged preferred,
    then the most frequently repeated spelling, then the first — deterministic at every
    step, because a name that changes between runs makes diffs unreadable.
    """
    candidates = [
        r for r in records
        if str(r.get("language") or "").lower() == language.lower() and r.get("vernacularName")
    ]
    if not candidates:
        return None

    preferred = [r for r in candidates if r.get("preferred")]
    pool = preferred or candidates

    counts: dict[str, int] = {}
    for record in pool:
        name = str(record["vernacularName"]).strip()
        counts[name] = counts.get(name, 0) + 1
    return max(sorted(counts), key=lambda name: counts[name])
