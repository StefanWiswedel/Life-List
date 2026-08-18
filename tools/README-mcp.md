# `lifelist_mcp.py` — a scoped MCP server for this repository

Nine named verbs, no shell. Lets Claude apply patches, push, run tests and kick off builds on
the machine holding the repo, without being able to touch anything else.

## Why it is shaped like this

A general `run(command)` tool is one typo away from operating on the wrong directory. On
18 Aug 2026 alone, working through a much narrower interface, Claude broke a git index, killed
its own shell twice with a careless `pkill`, and sent a patch containing a commit that had
already been applied. All of it was contained because it could not reach anything. This server
removes the friction and keeps the containment.

- **No shell.** Every tool builds a fixed argument vector with `shell=False`.
- **Pinned to the repo.** The working directory is forced; a path that resolves outside is
  refused after resolution, so `..`, absolute paths, drive letters and symlinks all fail the
  same check.
- **Allow-lists, not escaping.** Gradle tasks must be in a fixed set. Patch filenames must match
  `[A-Za-z0-9._-]+\.patch` and exist. Tags must look like `v1.2.3`.
- **No force push.** Rewriting published history stays a human decision.
- The guards are unit-tested in `training/tests/test_mcp_guards.py`.

## Tools

| tool | does |
|---|---|
| `git_status` | short status + last five commits |
| `git_apply_patch(filename)` | `git am` a `.patch` from the repo root |
| `git_am_abort` | recover from a failed apply |
| `git_push(tags=false)` | push `main`, or push tags |
| `git_tag(name)` | create a `vX.Y.Z` tag |
| `gradle(task)` | one of `:core:test`, `:app:assembleDebug`, `:app:assembleRelease`, `tasks` |
| `pytest_run` | the Python suite |
| `ruff_check` | the linter |
| `embed_status` | shard count and newest shard time |
| `job_result(id)` | collect a background job |
| `jobs` | every job this server has run |

`:app:installDebug` is deliberately absent: it waits for a device and would hang.

## Nothing blocks on a subprocess

The MCP client abandons a tool call after about 60 seconds. On this setup `git push`,
`git status` and `ruff` all take longer than that, and the first push through this server
reported a timeout for a push that had already succeeded. A tool that lies about failing is
worse than a slow one.

So every command runs on a thread and the tool returns a handle. If it finishes within
`FAST_ENOUGH_SECONDS` (8) the output comes back in the same call, which is the common case;
otherwise `job_result(id)` collects it whenever it is done. A command that cannot start at all
reports why rather than hanging until the timeout.

## Install (Windows)

```powershell
pip install mcp
```

Claude Desktop → **Settings → Developer → Edit Config**, which opens
`%APPDATA%\Claude\claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "lifelist": {
      "command": "python",
      "args": ["C:\\Users\\stefa\\Documents\\Projects\\Life-List\\tools\\lifelist_mcp.py"]
    }
  }
}
```

Restart Claude Desktop completely. The server appears under **Connectors**, and in a Cowork
session its tools arrive as `mcp__remote-devices__lifelist__*`.

If it does not appear, run the command manually to see the error, and check
`%APPDATA%\Claude\logs\mcp-server-lifelist.log`.

## Adding a verb

Add it to `build_server()`, allow-list its arguments in a pure function beside the others, and
test that function. The point of this file is that its entire security surface is four
functions that fit on one screen — keep it that way.
