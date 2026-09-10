"""Fetch the reference recordings and cut each to a listenable clip — build plan §3.6.

    lifelist-reference-audio --index shared/model/reference_audio.json \\
        --out app/src/main/assets/reference-audio -v

The sibling of `lifelist-reference-photos`, and it runs in the same place for the same reason:
the index is committed and small, the bytes belong to the archive, and every one carries its
recordist into the app.

**No key is needed here.** Building the index takes one (`lifelist-recordings`), but a
xeno-canto download URL is public, so a tagged build fetches these with no secret at all —
which is a better place to be than one GitHub secret away from a broken release.

Ten seconds of Opus at 24 kbit is about 30 KB, so seven hundred of them are 21 MB — the same
order as the 3,462 photographs already bundled, for something the app currently cannot do.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from ._common import LOG, setup_logging, shared_model

USER_AGENT = "LifeList/0.10 (https://github.com/StefanWiswedel/Life-List)"
CLIP_SECONDS = 10


def credit_line(entry: dict[str, Any]) -> str:
    """What the credits screen prints. Attribution is the licence, not a courtesy."""
    who = str(entry.get("recordist") or "").strip() or "unknown"
    return f"{who} · xeno-canto XC{entry.get('xc_id')}"


def ffmpeg_argv(source: Path, destination: Path, seconds: int = CLIP_SECONDS) -> list[str]:
    """Trim the quiet start, take [seconds], encode mono Opus.

    The leading silence matters: xeno-canto recordings often open with a few seconds of wind
    before the bird, and a ten-second clip that is eight seconds of nothing is a clip nobody
    plays twice. `silenceremove` cuts only the *leading* run, so a natural pause between two
    phrases of a song survives.

    Mono at 24 kbit because this is a bird at arm's length on a phone speaker, and because the
    alternative is a bundle four times the size for a difference nobody can hear there.
    """
    return [
        "ffmpeg", "-v", "error", "-y",
        "-i", str(source),
        "-af", "silenceremove=start_periods=1:start_threshold=-45dB:start_duration=0.1",
        "-t", str(seconds),
        "-ac", "1", "-c:a", "libopus", "-b:a", "24k",
        str(destination),
    ]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", type=Path, default=shared_model("reference_audio.json"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--seconds", type=int, default=CLIP_SECONDS)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)

    if not args.index.exists():
        LOG.error("%s not found — run lifelist-recordings first", args.index)
        return 1
    entries = json.loads(args.index.read_text(encoding="utf-8"))
    if args.limit:
        entries = entries[: args.limit]

    args.out.mkdir(parents=True, exist_ok=True)
    import requests
    from requests.adapters import HTTPAdapter

    session = requests.Session()
    session.mount("https://", HTTPAdapter(pool_connections=args.workers, pool_maxsize=args.workers))

    failures: list[tuple[int, str]] = []
    started = time.time()

    def fetch(entry: dict[str, Any]) -> None:
        taxon_id = int(entry["taxon_id"])
        destination = args.out / f"{taxon_id}.opus"
        if destination.exists() and destination.stat().st_size > 0:
            return
        source = args.out / f"{taxon_id}.download"
        try:
            response = session.get(
                str(entry["url"]), headers={"User-Agent": USER_AGENT}, timeout=120
            )
            response.raise_for_status()
            source.write_bytes(response.content)
            temporary = destination.with_suffix(".opus.tmp")
            subprocess.run(
                ffmpeg_argv(source, temporary, args.seconds),
                check=True,
                capture_output=True,
            )
            temporary.rename(destination)
        except Exception as exc:  # noqa: BLE001 — one dead recording is not a failed build
            failures.append((taxon_id, f"{type(exc).__name__}: {exc}"[:90]))
        finally:
            source.unlink(missing_ok=True)

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        list(pool.map(fetch, entries))

    credits = {
        str(entry["taxon_id"]): {
            "credit": credit_line(entry),
            "licence": str(entry.get("licence") or ""),
            "url": f"https://xeno-canto.org/{entry.get('xc_id')}",
        }
        for entry in entries
        if (args.out / f"{int(entry['taxon_id'])}.opus").exists()
    }
    (args.out / "credits.json").write_text(json.dumps(credits, indent=1) + "\n", encoding="utf-8")

    total = sum(p.stat().st_size for p in args.out.glob("*.opus"))
    LOG.info(
        "%d clips, %.1f MB, in %.0f s — %d failed",
        len(credits), total / 1e6, time.time() - started, len(failures),
    )
    for taxon_id, reason in failures[:20]:
        LOG.warning("  [%d] %s", taxon_id, reason)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
