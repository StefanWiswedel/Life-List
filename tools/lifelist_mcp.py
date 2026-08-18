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
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Gradle tasks this server will run. `:app:installDebug` is absent on purpose: it needs a
# device and would hang. Add to this list deliberately, never accept a task name verbatim.
ALLOWED_GRADLE_TASKS = frozenset(
    {":core:test", ":app:assembleDebug", ":app:assembleRelease", "tasks"}
)

PATCH_NAME = re.compile(r"^[A-Za-z0-9._-]+\.patch$")
MAX_OUTPUT = 20_000
TIMEOUT_SECONDS = 1800


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
    if len(output) > MAX_OUTPUT:
        output = output[:MAX_OUTPUT] + f"\n... truncated, {len(output) - MAX_OUTPUT} more chars"
    return f"exit {completed.returncode}\n{output}".strip()


def build_server():  # pragma: no cover — wiring, exercised by running it
    from mcp.server.fastmcp import FastMCP

    mcp = FastMCP("lifelist")

    @mcp.tool()
    def git_status() -> str:
        """Working tree status and the last few commits."""
        return run(["git", "status", "--short", "--branch"]) + "\n" + run(
            ["git", "log", "--oneline", "-5"]
        )

    @mcp.tool()
    def git_apply_patch(filename: str) -> str:
        """Apply a .patch file from the repository root with `git am`."""
        patch = validated_patch(filename)
        return run(["git", "am", str(patch)])

    @mcp.tool()
    def git_am_abort() -> str:
        """Abort a failed `git am`, restoring the working tree."""
        return run(["git", "am", "--abort"])

    @mcp.tool()
    def git_push(tags: bool = False) -> str:
        """Push main to origin. Never force: rewriting published history stays manual."""
        return run(["git", "push", "--tags"] if tags else ["git", "push"])

    @mcp.tool()
    def git_tag(name: str) -> str:
        """Create a version tag. Must look like v1.2.3."""
        if not re.match(r"^v\d+\.\d+\.\d+$", name):
            raise Refused(f"{name!r} is not a version tag like v0.2.0")
        return run(["git", "tag", name])

    @mcp.tool()
    def gradle(task: str) -> str:
        """Run one of a fixed set of Gradle tasks."""
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        return run([wrapper, validated_gradle_task(task), "--no-daemon"])

    @mcp.tool()
    def pytest_run() -> str:
        """Run the Python test suite."""
        return run([sys.executable, "-m", "pytest", "tests", "-q"], cwd=REPO / "training")

    @mcp.tool()
    def ruff_check() -> str:
        """Lint the Python sources."""
        return run(
            [sys.executable, "-m", "ruff", "check", "src", "tests", "tools"],
            cwd=REPO / "training",
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
