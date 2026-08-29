"""Tests for the MCP server's argument guards.

The server has no shell and no free-form command, so its entire security surface is these
four functions. They are tested here rather than trusted, because the failure mode is an
agent operating on a file outside the repository and nobody noticing until later.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("lifelist_mcp", REPO / "tools" / "lifelist_mcp.py")
mcp_module = importlib.util.module_from_spec(_spec)
# Registered before exec: @dataclass resolves its own module through sys.modules, and a
# module loaded by path alone is not in there, so the decorator raises on import.
sys.modules["lifelist_mcp"] = mcp_module
_spec.loader.exec_module(mcp_module)

Refused = mcp_module.Refused


def test_a_patch_in_the_repo_resolves():
    (REPO / "guard-test.patch").write_text("x", encoding="utf-8")
    try:
        assert mcp_module.validated_patch("guard-test.patch").name == "guard-test.patch"
    finally:
        (REPO / "guard-test.patch").unlink()


def test_a_traversing_path_is_refused():
    with pytest.raises(Refused):
        mcp_module.validated_patch("../../../etc/passwd.patch")


def test_an_absolute_path_is_refused():
    with pytest.raises(Refused):
        mcp_module.validated_patch("/etc/shadow.patch")


def test_a_windows_absolute_path_is_refused():
    with pytest.raises(Refused):
        mcp_module.validated_patch(r"C:\Windows\System32\evil.patch")


def test_a_non_patch_extension_is_refused():
    with pytest.raises(Refused):
        mcp_module.validated_patch("id_rsa")


def test_a_name_with_shell_metacharacters_is_refused():
    """Belt and braces: nothing is passed to a shell, but the name is still constrained."""
    with pytest.raises(Refused):
        mcp_module.validated_patch("a.patch; rm -rf /")


def test_a_missing_patch_is_refused_rather_than_run():
    with pytest.raises(Refused):
        mcp_module.validated_patch("definitely-not-here.patch")


def test_allowed_gradle_tasks_pass():
    assert mcp_module.validated_gradle_task(":core:test") == ":core:test"


def test_an_arbitrary_gradle_task_is_refused():
    with pytest.raises(Refused):
        mcp_module.validated_gradle_task("--stop")


def test_installdebug_is_not_allowed():
    """It needs a device and would hang the session waiting for one."""
    with pytest.raises(Refused):
        mcp_module.validated_gradle_task(":app:installDebug")


def test_resolve_inside_repo_accepts_a_subdirectory():
    assert mcp_module.resolve_inside_repo("training/pyproject.toml").is_file()


# -- background jobs ------------------------------------------------------------
#
# The MCP client abandons a call after ~60 s. The first `git push` through this server
# reported a timeout for a push that had already succeeded — a tool that lies about
# failing is worse than a slow one, so nothing blocks on a subprocess any more.


def test_a_fast_command_still_returns_its_output_in_one_call():
    out = mcp_module.start("echo", [mcp_module.sys.executable, "-c", "print('hello')"])

    assert "hello" in out
    assert "job_result" not in out, "a fast command should not need collecting"


def test_a_slow_command_returns_a_handle_rather_than_blocking():
    original = mcp_module.FAST_ENOUGH_SECONDS
    mcp_module.FAST_ENOUGH_SECONDS = 0.3
    try:
        out = mcp_module.start(
            "sleep", [mcp_module.sys.executable, "-c", "import time; time.sleep(3)"]
        )
    finally:
        mcp_module.FAST_ENOUGH_SECONDS = original

    assert "running" in out
    assert "job_result" in out


def test_a_handle_can_be_collected_once_the_work_finishes():
    import time

    original = mcp_module.FAST_ENOUGH_SECONDS
    mcp_module.FAST_ENOUGH_SECONDS = 0.1
    try:
        out = mcp_module.start(
            "slow", [mcp_module.sys.executable, "-c", "import time; time.sleep(1); print('done')"]
        )
    finally:
        mcp_module.FAST_ENOUGH_SECONDS = original
    job_id = out.split(" ")[0]

    for _ in range(60):
        job = mcp_module.JOBS[job_id]
        if job.result is not None:
            break
        time.sleep(0.1)

    assert "done" in mcp_module.JOBS[job_id].describe(job_id)


def test_a_command_that_cannot_start_reports_why_instead_of_hanging():

    original = mcp_module.FAST_ENOUGH_SECONDS
    mcp_module.FAST_ENOUGH_SECONDS = 2.0
    try:
        out = mcp_module.start("missing", ["definitely-not-a-real-binary-xyz"])
    finally:
        mcp_module.FAST_ENOUGH_SECONDS = original

    assert "failed to run" in out


def test_a_stage_name_is_chosen_from_a_list_never_taken_verbatim():
    """The whole point of this server: names, not commands."""
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
    import lifelist_mcp

    assert set(lifelist_mcp.PIPELINE_STAGES) == {
        "bridge", "taxonomy", "reference-index", "wikipedia", "export", "redlist",
        "thresholds",
    }
    for argv in lifelist_mcp.PIPELINE_STAGES.values():
        assert argv[0] == "-m", "every stage runs a module, not a script path or a shell string"


def test_training_is_not_a_stage_anything_can_start():
    """An hour-long run that writes the shipped head stays a deliberate act."""
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
    import lifelist_mcp

    assert not any("train" in name for name in lifelist_mcp.PIPELINE_STAGES)


def _server():
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
    import lifelist_mcp

    return lifelist_mcp


def test_a_long_log_keeps_its_ending():
    """The summary of a stage is its last line, and truncation used to eat exactly that."""
    server = _server()
    output = "".join(f"line {i}\n" for i in range(20_000)) + "THE ANSWER IS 3462\n"

    trimmed = server.trim(output)

    assert trimmed.endswith("THE ANSWER IS 3462\n")
    assert trimmed.startswith("line 0")
    assert "dropped from the middle" in trimmed


def test_a_short_log_is_untouched():
    server = _server()

    assert server.trim("all done") == "all done"


def test_only_generated_artefacts_can_be_committed():
    """Source reaches this repository as a patch, where it can be read as a diff first."""
    server = _server()

    assert server.COMMITTABLE == ("shared/model", "app/src/main/assets")


def test_a_commit_message_must_be_one_non_empty_line():
    """A body belongs in a patch, where it can be read before it lands."""
    import pytest

    server = _server()

    assert server.validated_message("  The >=20 model  ") == "The >=20 model"
    for bad in ("", "   ", "a title\n\nand a body"):
        with pytest.raises(server.Refused):
            server.validated_message(bad)
