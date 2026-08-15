#!/usr/bin/env bash
# claudeProcessWrapper for the worktree-rooted config-home model.
#
# The VSCode Claude extension launches Claude via this script, passing the real
# claude binary (and its args) as "$@". We set CLAUDE_CONFIG_DIR to the current
# worktree's .claude/hub (the config home) computed from the git root of the cwd,
# then exec the real binary. This is what makes ONE fixed wrapper path serve EVERY
# worktree: the extension can't substitute ${workspaceFolder}, but the wrapper
# resolves the config dir dynamically at launch.
#
# The extension invokes the wrapper TWICE per startup with different cwds:
#   - the main session: cwd = the workspace folder (the worktree) -> we set the var
#   - `auth status --json`: cwd = / -> no git root, we fall through WITHOUT setting
#     it (harmless here: auth is Bedrock/AWS-SSO, external to the config dir).
# So the guard below is required, not defensive paranoia.
set -euo pipefail

root="$(git -C "$PWD" rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -n "$root" && -d "$root/.claude/hub" ]]; then
  export CLAUDE_CONFIG_DIR="$root/.claude"
fi

exec "$@"
