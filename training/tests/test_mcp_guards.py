"""Tests for the MCP server's argument guards.

The server has no shell and no free-form command, so its entire security surface is these
four functions. They are tested here rather than trusted, because the failure mode is an
agent operating on a file outside the repository and nobody noticing until later.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("lifelist_mcp", REPO / "tools" / "lifelist_mcp.py")
mcp_module = importlib.util.module_from_spec(_spec)
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
