"""Turn stage 2's taxon-id counts into something a human can decide on.

The threshold table alone says "8,000 taxa at 50". It does not say whether those are
birds you would want in the app or 4,000 near-identical fungi that will wreck the
rollup's calibration. This joins iNaturalist's taxa table back on and breaks the
survivors down by group.

Reads what tools/stage2_sequenced.py cached. Decides nothing.
"""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from lifelist_train.inat import coverage, select_taxa, taxa_at_thresholds

CACHE = Path("cache")
TAXA_GZ = Path("inat-work/taxa.csv.gz")
THRESHOLDS = (50, 80, 120, 200)

# iNaturalist taxon ids for the groups the app's UI groups by (BUILD.md §4.2).
# Order matters: first match wins, so Insecta is tested before Animalia.
GROUPS = [
    ("Birds", 3),
    ("Mammals", 40151),
    ("Reptiles", 26036),
    ("Amphibians", 20978),
    ("Ray-finned fish", 47178),
    ("Insects", 47158),
    ("Arachnids", 47119),
    ("Molluscs", 47115),
    ("Other animals", 1),
    ("Fungi", 47170),
    ("Plants", 47126),
]


def group_of(ancestry: str | float, taxon_id: int) -> str:
    if not isinstance(ancestry, str):
        return "Unplaced"
    chain = {int(x) for x in ancestry.split("/") if x.isdigit()}
    chain.add(taxon_id)
    for label, key in GROUPS:
        if key in chain:
            return label
    return "Other"


def main() -> int:
    joined = pd.read_parquet(CACHE / "stage2_joined.parquet")
    coverages = coverage(joined)
    counts = taxa_at_thresholds(coverages, THRESHOLDS)

    taxa = pd.read_csv(
        TAXA_GZ, sep="\t", usecols=["taxon_id", "ancestry", "rank", "name"], low_memory=False
    )
    taxa["group"] = [
        group_of(a, t) for a, t in zip(taxa["ancestry"], taxa["taxon_id"], strict=False)
    ]
    lookup = taxa.set_index("taxon_id")

    obs = {c.taxon_id: c.observations for c in coverages}
    photos = {c.taxon_id: c.photos for c in coverages}

    out: list[str] = []

    def say(line: str = "") -> None:
        print(line)
        out.append(line)

    say()
    say("Taxa surviving each minimum-observation threshold")
    say("(observations, not photos — ten photos of one beetle is one piece of evidence)")
    say()
    say(f"{'min obs':>9}  {'taxa':>7}  {'photos (capped 500/taxon)':>26}")
    say(f"{'-' * 9}  {'-' * 7}  {'-' * 26}")
    for t in THRESHOLDS:
        selected = select_taxa(coverages, t, 500)
        say(f"{t:>9}  {counts[t]:>7}  {sum(selected.values()):>26,}")
    say()

    say("What survives, by group")
    say()
    header = f"{'group':<17}" + "".join(f"{t:>9}" for t in THRESHOLDS)
    say(header)
    say("-" * len(header))
    rows: dict[str, list[int]] = {}
    for t in THRESHOLDS:
        keep = {c.taxon_id for c in coverages if c.observations >= t}
        for label, _ in GROUPS + [("Other", 0), ("Unplaced", 0)]:
            rows.setdefault(label, [0] * len(THRESHOLDS))
        for tid in keep:
            label = lookup["group"].get(tid, "Unplaced")
            rows.setdefault(label, [0] * len(THRESHOLDS))[THRESHOLDS.index(t)] += 1
    for label, values in rows.items():
        if any(values):
            say(f"{label:<17}" + "".join(f"{v:>9,}" for v in values))
    say()

    say("Twenty commonest taxa (by Danish research-grade observations with open photos)")
    say()
    top = sorted(coverages, key=lambda c: c.observations, reverse=True)[:20]
    say(f"{'observations':>13}  {'photos':>8}  {'group':<16}  name")
    say(f"{'-' * 13}  {'-' * 8}  {'-' * 16}  {'-' * 32}")
    for c in top:
        name = lookup["name"].get(c.taxon_id, f"(iNat {c.taxon_id})")
        grp = lookup["group"].get(c.taxon_id, "?")
        say(f"{obs[c.taxon_id]:>13,}  {photos[c.taxon_id]:>8,}  {grp:<16}  {name}")
    say()
    say("Stopping here. The threshold sets the model's output dimension and every")
    say("accuracy figure downstream — it wants a human decision, not a default.")
    say()

    (CACHE / "stage2_threshold_report.txt").write_text("\n".join(out) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
