"""A deliberately small MCP server: a few named verbs, pinned to this repository.

Why not a shell. A general `run(command)` tool is one typo away from operating on the
wrong directory, and the agent calling it cannot be trusted not to make that typo — in a
single day this one broke a git index, killed its own shell twice with a careless pkill,
and sent a patch containing a commit that had already been applied. All of that was
contained because it could not reach anything. This server is the smallest thing that
removes the friction without removing the containment.

So: no shell, ever. Every tool builds a fixed argument vector, `shell=False`, with the
working directory forced to the repository root. Arguments are validated against what
they are allowed to be, not escaped and hoped for.

Install (on the machine holding the repo):

    pip install mcp
    # claude_desktop_config.json
    # "lifelist": {"command": "python", "args": ["C:/.../Life-List/tools/lifelist_mcp.py"]}
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Gradle tasks this server will run. `:app:installDebug` is absent on purpose: it needs a
# device and would hang. Add to this list deliberately, never accept a task name verbatim.
ALLOWED_GRADLE_TASKS = frozenset(
    {":core:test", ":app:assembleDebug", ":app:assembleRelease", "tasks"}
)

# The long-running pipeline stages, as named verbs with fixed argument vectors.
#
# Same rule as everything else here: the caller picks a name from this dict, never a command.
# `train` is absent on purpose — it takes an hour and writes the shipped head, so it stays a
# thing a person starts deliberately. Paths are relative to `training/`, which is the cwd
# these run in.
PIPELINE_STAGES: dict[str, list[str]] = {
    # Rebuild the iNaturalist->GBIF crossing from stage 1's dump. Two minutes; needed when the
    # document's *shape* changes, not when the model does.
    "bridge": [
        "-m", "lifelist_train.cli.bridge",
        "--taxa-raw", "cache/taxa_raw.json.gz",
        "--joined", "cache/stage2_joined",
        "--min-observations", "20",
    ],
    # Rewrite taxonomy.json alone, refusing if the leaf ordering moved under the shipped head.
    "taxonomy": [
        "-m", "lifelist_train.cli.train",
        "--cache-dir", "cache",
        "--min-observations", "20",
        "--taxonomy-only",
    ],
    # Rebuild the reference-photo index for every leaf in the current taxonomy. Two minutes
    # of iNaturalist API at their asked-for one request a second.
    "reference-index": [
        "-m", "lifelist_train.cli.reference_index",
        "--bridge", "../shared/model/taxon_bridge.json",
        "--taxonomy", "../shared/model/taxonomy.json",
        "--out", "../shared/model/reference_photos.json",
    ],
    # Resumable and incremental: only titles that are neither cached nor known-absent.
    "wikipedia": ["-m", "lifelist_train.cli.wikipedia"],
    # The 350 MB ONNX. CI does this on every tag, so running it here is for checking that it
    # still works before spending a release on finding out that it does not.
    "export": [
        "-m", "lifelist_train.cli.export",
        "--head", "../shared/model/head.npz",
        "--meta", "../shared/model/model_meta.json",
        "--out", "../app/src/main/assets/lifelist.onnx",
    ],
}

# Directories whose contents this server may stage and commit. Generated artefacts only:
# the model, and the assets CI copies it into. Source is committed by patch, where it can be
# read as a diff before it lands.
COMMITTABLE = ("shared/model", "app/src/main/assets")

PATCH_NAME = re.compile(r"^[A-Za-z0-9._-]+\.patch$")
MAX_OUTPUT = 20_000
TIMEOUT_SECONDS = 1800

# The MCP client gives up on a tool call after ~60 s. Measured on this setup, `git push`,
# `ruff` and `git status` all exceed that — and the first time it happened the push had in
# fact succeeded, so the agent was told "timed out" about work that was done. A tool that
# lies about failing is worse than a slow one, so anything that spawns a process returns a
# job id immediately and the result is collected separately.
FAST_ENOUGH_SECONDS = 8


class Refused(ValueError):
    """The request was well-formed but not something this server will do."""


def resolve_inside_repo(name: str) -> Path:
    """Resolve a filename against the repo root, refusing anything that escapes it.

    Checked after resolution rather than by inspecting the string: `..` is the obvious
    attack and the least interesting one. Symlinks, absolute paths and Windows drive
    letters all fail the same check without needing to be enumerated.
    """
    candidate = (REPO / name).resolve()
    if not candidate.is_relative_to(REPO):
        raise Refused(f"{name} resolves outside the repository")
    return candidate


def validated_patch(name: str) -> Path:
    if not PATCH_NAME.match(name):
        raise Refused(f"{name!r} is not a plain .patch filename")
    path = resolve_inside_repo(name)
    if not path.is_file():
        raise Refused(f"{name} does not exist in the repository")
    return path


def validated_message(message: str) -> str:
    """A commit message: one non-empty line.

    A body belongs in a patch, where it can be written and read before it lands, rather than
    squeezed through a tool argument by something that cannot see the result.
    """
    cleaned = message.strip()
    if not cleaned:
        raise Refused("a commit needs a message")
    if "\n" in cleaned:
        raise Refused("one line, please — a body belongs in a patch")
    return cleaned


def validated_gradle_task(task: str) -> str:
    if task not in ALLOWED_GRADLE_TASKS:
        raise Refused(f"{task!r} is not in the allowed set: {sorted(ALLOWED_GRADLE_TASKS)}")
    return task


def run(argv: list[str], cwd: Path | None = None) -> str:
    """Run a fixed argument vector. No shell, no interpolation, always inside the repo."""
    completed = subprocess.run(  # noqa: S603 — argv is fixed by the caller, shell=False
        argv,
        cwd=str(cwd or REPO),
        capture_output=True,
        text=True,
        timeout=TIMEOUT_SECONDS,
        shell=False,
    )
    output = (completed.stdout or "") + (completed.stderr or "")
    return f"exit {completed.returncode}\n{trim(output)}".strip()


def trim(output: str) -> str:
    """Keep the head and the tail, drop the middle.

    It used to keep the first 20,000 characters, which is precisely the wrong half. A stage
    that fetches 3,500 taxa says what it fetched at the *end* — how many crossed, how many
    fell back, what it wrote — and every one of those lines was cut while several hundred
    lines of HTTP debug survived. Twice in one afternoon the summary had to be recovered by
    reading the artefact instead.
    """
    if len(output) <= MAX_OUTPUT:
        return output
    head = output[: MAX_OUTPUT // 4]
    tail = output[-(MAX_OUTPUT * 3 // 4) :]
    dropped = len(output) - len(head) - len(tail)
    return f"{head}\n... {dropped} characters dropped from the middle ...\n{tail}"


@dataclass
class Job:
    """One background command and whatever it has produced so far."""

    label: str
    started: float = field(default_factory=time.monotonic)
    finished: float | None = None
    result: str | None = None

    @property
    def elapsed(self) -> float:
        return (self.finished or time.monotonic()) - self.started

    def describe(self, job_id: str) -> str:
        if self.result is None:
            return f"{job_id} — {self.label}: running, {self.elapsed:.0f}s elapsed"
        return f"{job_id} — {self.label}: finished in {self.elapsed:.0f}s\n{self.result}"


JOBS: dict[str, Job] = {}
_JOBS_LOCK = threading.Lock()


def run_all(argvs: list[list[str]], cwd: Path | None = None) -> str:
    """Run commands in order, stopping at the first failure."""
    parts = []
    for argv in argvs:
        result = run(argv, cwd)
        parts.append(result)
        if not result.startswith("exit 0"):
            break
    return "\n".join(parts)


def start(label: str, argv: list[str], cwd: Path | None = None) -> str:
    """Run in the background and return a job id immediately.

    Returning a handle rather than blocking is what keeps the tool honest: the client's
    timeout no longer decides whether the caller is told the truth about what happened.
    """
    job_id = f"job-{uuid.uuid4().hex[:6]}"
    job = Job(label=label)
    with _JOBS_LOCK:
        JOBS[job_id] = job

    def work() -> None:
        try:
            output = run_all(argv, cwd) if argv and isinstance(argv[0], list) else run(argv, cwd)
        except Exception as exc:  # noqa: BLE001 — the message is the whole point
            output = f"failed to run: {type(exc).__name__}: {exc}"
        with _JOBS_LOCK:
            job.result = output
            job.finished = time.monotonic()

    threading.Thread(target=work, daemon=True, name=job_id).start()

    # Most commands finish quickly. Wait briefly so the common case still reads as one call.
    deadline = time.monotonic() + FAST_ENOUGH_SECONDS
    while time.monotonic() < deadline:
        with _JOBS_LOCK:
            if job.result is not None:
                return job.describe(job_id)
        time.sleep(0.2)
    return job.describe(job_id) + "\n(call job_result to collect it)"


def build_server():  # pragma: no cover — wiring, exercised by running it
    from mcp.server.fastmcp import FastMCP

    mcp = FastMCP("lifelist")

    @mcp.tool()
    def git_status() -> str:
        """Working tree status and the last few commits."""
        return start("git status", ["git", "status", "--short", "--branch"])

    @mcp.tool()
    def git_apply_patch(filename: str) -> str:
        """Apply a .patch file from the repository root with `git am`."""
        patch = validated_patch(filename)
        return start(f"git am {patch.name}", ["git", "am", str(patch)])

    @mcp.tool()
    def git_am_abort() -> str:
        """Abort a failed `git am`, restoring the working tree."""
        return start("git am --abort", ["git", "am", "--abort"])

    @mcp.tool()
    def commit_artefacts(message: str) -> str:
        """Stage and commit the generated artefacts under shared/model and the app's assets.

        The gap this fills: every other commit reaches this repository as a patch, and `git am`
        stages and commits in one step. Artefacts are the one thing patches cannot carry —
        they are produced *here*, they are megabytes of binary, and a 12 MB patch is not a
        thing to send. So they sat uncommitted until a person typed `git add` themselves.

        Deliberately not `git add <anything>`: the paths are fixed, so this can add a
        regenerated index and never a stray file from somewhere else in the tree.
        """
        existing = [d for d in COMMITTABLE if (REPO / d).is_dir()]
        return start(
            "commit artefacts",
            ["git", "commit", "-m", validated_message(message), "--", *existing],
        )

    @mcp.tool()
    def git_push(tags: bool = False) -> str:
        """Push main to origin, and the tags too if asked. Never force.

        `tags=True` pushes the branch *and then* the tags, rather than `git push --tags`,
        which pushes only the tags. That difference cost a release: the tag was on origin and
        CI built from it, while main sat two commits behind with the model commit existing
        nowhere but one laptop.
        """
        if tags:
            return start("git push + tags", [["git", "push"], ["git", "push", "--tags"]])
        return start("git push", ["git", "push"])

    @mcp.tool()
    def git_tag(name: str) -> str:
        """Create a version tag. Must look like v1.2.3."""
        if not re.match(r"^v\d+\.\d+\.\d+$", name):
            raise Refused(f"{name!r} is not a version tag like v0.2.0")
        return start(f"git tag {name}", ["git", "tag", name])

    @mcp.tool()
    def gradle(task: str) -> str:
        """Run one of a fixed set of Gradle tasks."""
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        return start(f"gradle {task}", [wrapper, validated_gradle_task(task), "--no-daemon"])

    @mcp.tool()
    def stage(name: str) -> str:
        """Run one named pipeline stage: reference-index, wikipedia or export.

        Not a shell and not a command: `name` selects a fixed argument vector. Training is
        deliberately not on the list — an hour-long run that writes the shipped head is
        something a person should start on purpose.
        """
        argv = PIPELINE_STAGES.get(name)
        if argv is None:
            raise Refused(f"{name!r} is not a stage; known: {sorted(PIPELINE_STAGES)}")
        return start(f"stage {name}", [sys.executable, *argv], cwd=REPO / "training")

    @mcp.tool()
    def pytest_run() -> str:
        """Run the Python test suite."""
        return start(
            "pytest", [sys.executable, "-m", "pytest", "tests", "-q"], cwd=REPO / "training"
        )

    @mcp.tool()
    def ruff_check() -> str:
        """Lint the Python sources."""
        return start(
            "ruff", [sys.executable, "-m", "ruff", "check", "src", "tests", "tools"],
            cwd=REPO / "training",
        )

    @mcp.tool()
    def job_result(job_id: str) -> str:
        """Collect a background job started by any of the tools above."""
        with _JOBS_LOCK:
            job = JOBS.get(job_id)
        if job is None:
            known = ", ".join(sorted(JOBS)) or "none"
            raise Refused(f"no job {job_id!r}; known jobs: {known}")
        return job.describe(job_id)

    @mcp.tool()
    def jobs() -> str:
        """Every job this server has run, newest last."""
        with _JOBS_LOCK:
            if not JOBS:
                return "no jobs yet"
            return "\n".join(
                job.describe(job_id).splitlines()[0] for job_id, job in JOBS.items()
            )

    @mcp.tool()
    def embed_status() -> str:
        """How far stage 3 has got: shard count and the newest shard's timestamp."""
        directory = REPO / "training" / "cache" / "embeddings"
        if not directory.is_dir():
            return "no embeddings directory yet"
        shards = sorted(directory.glob("shard-*.npz"))
        if not shards:
            return "0 shards"
        newest = max(shards, key=lambda p: p.stat().st_mtime)
        return f"{len(shards)} shards, newest {newest.name} at {newest.stat().st_mtime:.0f}"

    return mcp


if __name__ == "__main__":  # pragma: no cover
    build_server().run()
