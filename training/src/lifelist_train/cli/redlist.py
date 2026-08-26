"""Build `shared/model/redlist.json` from Den danske Rødliste.

    lifelist-redlist

13,899 assessments over 695 pages, about twelve minutes at their pace. Run it when the model's
vocabulary changes, or when a new assessment round lands — the API takes `vurderingsRunde`, and
`latest` is what it sounds like.

The terms are short and worth reading: the data may be used free of charge by anyone, as they
are, and *not without proper citation*. The citation travels inside the artefact rather than in
a comment here, so whatever ships it has no excuse.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

from ..redlist import API, document, family_totals, match
from ._common import LOG, setup_logging, shared_model


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Fetch the Danish Red List")
    parser.add_argument("--taxonomy", type=Path, default=shared_model("taxonomy.json"))
    parser.add_argument("--out", type=Path, default=shared_model("redlist.json"))
    parser.add_argument("--round", default="latest", help="assessment round: latest, 2019, 2010")
    parser.add_argument("--pause", type=float, default=0.05)
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def fetch_all(assessment_round: str, pause: float) -> list[dict[str, Any]]:
    """Every assessment, page by page.

    Paged rather than streamed and there is no bulk file, so this is the only way in. It is
    also why the result is committed: nobody should need the network to know that a Dark
    Spinach is Least Concern.
    """
    import requests

    session = requests.Session()
    rows: list[dict[str, Any]] = []
    page, pages = 1, None
    while pages is None or page <= pages:
        response = session.get(
            API, params={"vurderingsRunde": assessment_round, "page": page}, timeout=40
        )
        response.raise_for_status()
        payload = response.json()
        pages = payload["pages"]
        rows.extend(payload["data"])
        if page % 100 == 0:
            LOG.info("  page %d/%d, %d assessments", page, pages, len(rows))
        page += 1
        time.sleep(pause)
    return rows


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    if not args.taxonomy.exists():
        LOG.error("%s not found — the taxonomy says which species to look up", args.taxonomy)
        return 1
    nodes = json.loads(args.taxonomy.read_text(encoding="utf-8"))
    leaves = [n for n in nodes if n.get("leaf_index") is not None]

    LOG.info("fetching assessments (round %s)", args.round)
    assessments = fetch_all(args.round, args.pause)

    categories, danish, report = match(assessments, leaves)
    totals = family_totals(assessments)
    LOG.info("%s", report.summary())
    LOG.info("%d families with a Danish total", len(totals))

    if not categories:
        LOG.error("nothing matched — the taxonomy and the Red List share no names, which is a "
                  "bug rather than a fact about Denmark")
        return 1

    doc = document(categories, danish, totals, time.strftime("%Y-%m-%d"))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.out.with_suffix(args.out.suffix + ".tmp")
    temporary.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    temporary.replace(args.out)
    LOG.info("wrote %s (%.0f KB)", args.out, args.out.stat().st_size / 1024)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
