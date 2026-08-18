"""Fetch the reference photographs the identification screen compares against.

The index — which photo belongs to which taxon, who took it, under what licence — is
committed at `shared/model/reference_photos.json`, 294 KB. The images themselves are not:
2,294 JPEGs are tens of megabytes of other people's work, and the bucket is the canonical
copy. CI materialises them the same way it materialises the model.

    lifelist-reference-photos --index shared/model/reference_photos.json \\
        --out app/src/main/assets/reference

**Attribution is not optional.** These are CC-BY, CC-BY-NC, CC-BY-SA and CC0 photographs by
24,187 named people. Every one carries its photographer and licence into the app; a taxon
whose credit is missing is skipped rather than shipped uncredited.
"""

from __future__ import annotations

import argparse
import json
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from ..inat import photo_url
from ._common import LOG, setup_logging

# 240 px. Big enough to see a wing bar against your own photo, small enough that 2,294 of
# them are tens of megabytes rather than hundreds.
DEFAULT_SIZE = "small"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Download reference photos into app assets")
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--size", default=DEFAULT_SIZE)
    parser.add_argument("--workers", type=int, default=24)
    parser.add_argument("--limit", type=int, default=None, help="stop after N, for testing")
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def usable(entry: dict) -> bool:
    """A photo with no credit is not shippable, whatever its licence says."""
    return bool(entry.get("credit")) and bool(entry.get("licence"))


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    entries = json.loads(args.index.read_text(encoding="utf-8"))
    shippable = [e for e in entries if usable(e)]
    if len(shippable) != len(entries):
        LOG.warning(
            "%d of %d reference photos have no credit and were skipped",
            len(entries) - len(shippable),
            len(entries),
        )
    if args.limit:
        shippable = shippable[: args.limit]

    args.out.mkdir(parents=True, exist_ok=True)
    import requests
    from requests.adapters import HTTPAdapter

    session = requests.Session()
    session.mount("https://", HTTPAdapter(pool_connections=args.workers, pool_maxsize=args.workers))

    failures: list[tuple[int, str]] = []
    started = time.time()

    def fetch(entry: dict) -> None:
        destination = args.out / f"{entry['taxon_id']}.jpg"
        if destination.exists() and destination.stat().st_size > 0:
            return
        try:
            response = session.get(
                photo_url(int(entry["photo_id"]), str(entry["extension"]), args.size), timeout=30
            )
            response.raise_for_status()
            temporary = destination.with_suffix(".jpg.tmp")
            temporary.write_bytes(response.content)
            temporary.rename(destination)
        except Exception as exc:  # noqa: BLE001 — one dead photo is not a failed build
            failures.append((int(entry["taxon_id"]), f"{type(exc).__name__}: {exc}"[:80]))

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        list(pool.map(fetch, shippable))

    written = sorted(args.out.glob("*.jpg"))
    total = sum(p.stat().st_size for p in written)
    LOG.info(
        "%d photos, %.1f MB, %d failed, %.0f s",
        len(written),
        total / 1e6,
        len(failures),
        time.time() - started,
    )

    # The credits ride with the images, so the app never has to ask the network who took one.
    credits = {
        str(e["taxon_id"]): {"credit": e["credit"], "licence": e["licence"]}
        for e in shippable
        if (args.out / f"{e['taxon_id']}.jpg").exists()
    }
    (args.out / "credits.json").write_text(
        json.dumps(credits, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8"
    )
    LOG.info("wrote credits.json — %d photographers credited", len(credits))

    for taxon_id, reason in failures[:10]:
        LOG.warning("  taxon %s: %s", taxon_id, reason)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
