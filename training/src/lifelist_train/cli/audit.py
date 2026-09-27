"""A page that says where the checklist is weakest.

    lifelist-audit --out checklist-audit.html -v

Not a list to read. A list sorted so the questionable part is at the top: species Denmark's
register knows only by genus, rarest first. See `lifelist_train.audit` for why that is the
right axis and VERIFICATION.md section 92.
"""

from __future__ import annotations

import argparse
import html
import json
from collections import Counter
from pathlib import Path

from ..audit import CLAUSES, rows, weakest_first
from ..register import Register
from ._common import LOG, add_common_args, cache_path, setup_logging, shared_model

#: The app's own grouping, so the report and the phone agree about what a bird is.
GROUPS = [
    ("Birds", 212), ("Mammals", 359), ("Reptiles", 11592253), ("Reptiles", 358),
    ("Amphibians", 131), ("Fish", 44), ("Insects", 216), ("Arachnids", 367),
    ("Crustaceans", 229), ("Molluscs", 52), ("Worms", 42), ("Plants", 6), ("Fungi", 5),
]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checklist", type=Path, default=shared_model("checklist.json"))
    parser.add_argument("--register", type=Path, default=Path("cache/danish_register.json"))
    parser.add_argument("--taxonomy", type=Path, default=shared_model("taxonomy.json"))
    parser.add_argument("--photos", type=Path, default=shared_model("checklist_photos.json"))
    parser.add_argument("--country", default="DK")
    parser.add_argument("--out", type=Path, default=Path("checklist-audit.html"))
    parser.add_argument(
        "--limit",
        type=int,
        default=400,
        help="how many of the weakest rows to show per group",
    )
    return add_common_args(parser)


def group_of(lineage: list[int]) -> str:
    seen = set(lineage)
    for label, key in GROUPS:
        if key in seen:
            return label
    return "Other"


def load_synonyms(path: Path) -> dict[int, list[str]]:
    if not path.exists():
        return {}
    out: dict[int, list[str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if "synonyms" in row:
            out[int(row["key"])] = row["synonyms"]
    return out


def page(document: dict, by_group: dict[str, list], limit: int, totals: Counter) -> str:
    e = html.escape
    order = sorted(by_group, key=lambda g: -len(by_group[g]))
    nav = "".join(
        f'<a href="#{e(g.lower())}">{e(g)} <span>{len(by_group[g])}</span></a>' for g in order
    )

    sections = []
    for g in order:
        shown = weakest_first(by_group[g], limit)
        weak = sum(1 for r in by_group[g] if r.weak)
        body = "".join(
            "<tr class='{cls}'>"
            "<td class='sci'>{name}</td><td>{ven}</td><td class='fam'>{fam}</td>"
            "<td class='num'>{rec}</td><td><span class='c c-{clause}'>{clause}</span></td>"
            "<td class='mark'>{cam}</td><td class='mark'>{pic}</td></tr>".format(
                cls="weak" if r.weak else "",
                name=e(r.scientific_name),
                ven=e(r.vernacular_en or "—"),
                fam=e(r.family or "—"),
                rec=f"{r.records:,}",
                clause=e(r.clause),
                cam="●" if r.identifiable else "",
                pic="●" if r.has_photo else "",
            )
            for r in shown
        )
        sections.append(
            f"<section id='{e(g.lower())}'><h2>{e(g)}</h2>"
            f"<p class='lead'>{len(by_group[g]):,} species. "
            f"<strong>{weak:,}</strong> held by genus alone — those are first, rarest at the top. "
            f"Showing {min(len(shown), limit):,}.</p>"
            "<table><thead><tr><th>Species</th><th>English</th><th>Family</th>"
            "<th class='num'>DK records</th><th>Vouched by</th><th>Camera</th><th>Photo</th>"
            f"</tr></thead><tbody>{body}</tbody></table></section>"
        )

    counts = " · ".join(f"{c} <em>{totals[c]:,}</em>" for c in CLAUSES if totals[c])
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Checklist audit</title>
<style>
  :root {{
    --bone:#F7F4ED; --surface:#FDFCF9; --ink:#22201C; --muted:#5F5B54;
    --rust:#A85331; --sage:#7C8471; --ochre:#B8892B; --line:#E4DED1;
  }}
  * {{ box-sizing:border-box; }}
  body {{ margin:0; background:var(--bone); color:var(--ink);
    font:15px/1.55 ui-serif,Georgia,'Iowan Old Style',serif; }}
  header {{ padding:48px 24px 20px; max-width:1100px; margin:0 auto; }}
  h1 {{ margin:0 0 6px; font-size:30px; font-weight:600; letter-spacing:-.01em; }}
  .sub {{ color:var(--muted); max-width:62ch; }}
  .tally {{ margin-top:14px; font-size:13px; color:var(--muted); }}
  .tally em {{ font-style:normal; color:var(--ink); font-weight:600;
    font-variant-numeric:tabular-nums; }}
  nav {{ position:sticky; top:0; z-index:2; background:var(--bone);
    border-bottom:1px solid var(--line); padding:10px 24px; }}
  nav div {{ max-width:1100px; margin:0 auto; display:flex; flex-wrap:wrap; gap:6px; }}
  nav a {{ font:600 11.5px/1 ui-sans-serif,system-ui,sans-serif; letter-spacing:.06em;
    text-transform:uppercase; color:var(--muted); text-decoration:none;
    padding:7px 10px; border:1px solid var(--line); border-radius:999px;
    background:var(--surface); }}
  nav a span {{ color:var(--rust); font-variant-numeric:tabular-nums; }}
  nav a:hover {{ border-color:var(--rust); color:var(--ink); }}
  main {{ max-width:1100px; margin:0 auto; padding:0 24px 80px; }}
  section {{ margin-top:44px; }}
  h2 {{ font-size:21px; margin:0 0 4px; }}
  .lead {{ color:var(--muted); font-size:13.5px; margin:0 0 14px; }}
  table {{ width:100%; border-collapse:collapse; background:var(--surface);
    border:1px solid var(--line); border-radius:10px; overflow:hidden; }}
  th {{ text-align:left; font:600 10.5px/1 ui-sans-serif,system-ui,sans-serif;
    letter-spacing:.07em; text-transform:uppercase; color:var(--muted);
    padding:11px 12px; border-bottom:1px solid var(--line); white-space:nowrap; }}
  td {{ padding:8px 12px; border-bottom:1px solid #F0EBE0; font-size:13.5px; }}
  tr:last-child td {{ border-bottom:0; }}
  tr.weak td {{ background:#FBF3EE; }}
  .sci {{ font-style:italic; }}
  .fam {{ color:var(--muted); }}
  .num {{ text-align:right; font-variant-numeric:tabular-nums; }}
  .mark {{ text-align:center; color:var(--sage); }}
  .c {{ font:600 10.5px/1 ui-sans-serif,system-ui,sans-serif; letter-spacing:.05em;
    text-transform:uppercase; padding:4px 7px; border-radius:4px; white-space:nowrap; }}
  .c-name {{ background:#EDF0EA; color:#4C5744; }}
  .c-synonym {{ background:#F7EFDC; color:#7A5B14; }}
  .c-genus {{ background:#F6E2D9; color:#8A3E22; }}
  .c-unvouched {{ background:#EFE7E4; color:#7A2D2D; }}
  @media (max-width:640px) {{ .fam, th:nth-child(3) {{ display:none; }} }}
</style></head><body>
<header>
  <h1>Checklist audit</h1>
  <p class="sub">Every species here cleared two tests: GBIF has five or more Danish
  <em>observations</em> of it, and Denmark's national register vouches for it. The second test
  has three clauses and they are not equally strong. This sorts by how far the rule had to
  stretch — <strong>genus alone first, rarest at the top</strong> — so the questionable part is
  a few hundred rows rather than {sum(totals.values()):,}.</p>
  <p class="tally">{counts}</p>
</header>
<nav><div>{nav}</div></nav>
<main>{''.join(sections)}</main>
</body></html>"""


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    if not args.register.exists():
        LOG.error("no %s — run `lifelist-register` first", args.register)
        return 1
    document = json.loads(args.checklist.read_text(encoding="utf-8"))
    register = Register.from_document(json.loads(args.register.read_text(encoding="utf-8")))

    groups = {
        int(key): group_of(entry.get("lineage") or [])
        for key, entry in (document.get("families") or {}).items()
    }
    identifiable = frozenset(
        int(n["taxon_id"])
        for n in json.loads(args.taxonomy.read_text(encoding="utf-8"))
        if n.get("rank") == "species" and int(n["taxon_id"]) > 0
    ) if args.taxonomy.exists() else frozenset()
    photographed = frozenset(
        int(r["taxon_id"]) for r in json.loads(args.photos.read_text(encoding="utf-8"))
    ) if args.photos.exists() else frozenset()

    synonym_cache = cache_path(args.cache_dir, f"checklist_synonyms_{args.country}.jsonl")
    table = rows(
        document,
        register,
        groups,
        synonyms=load_synonyms(synonym_cache),
        identifiable=identifiable,
        photographed=photographed,
    )
    by_group: dict[str, list] = {}
    for row in table:
        by_group.setdefault(row.group, []).append(row)
    totals = Counter(row.clause for row in table)

    args.out.write_text(page(document, by_group, args.limit, totals), encoding="utf-8")
    LOG.info(
        "%s: %d species — %s",
        args.out, len(table),
        ", ".join(f"{count} by {clause}" for clause, count in totals.most_common()),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
