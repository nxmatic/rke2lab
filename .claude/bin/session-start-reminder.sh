#!/usr/bin/env bash
# SessionStart hook: remind Claude to update checkpoint draft if stale or missing.
#
# This ensures the checkpoint draft stays current during long sessions, so recovery
# points contain fresh context rather than stale state from 50+ turns ago.
set -euo pipefail

input="$(cat)"
session_id="$(printf '%s' "$input" | yq -p json '.session_id // ""')"

# Can't check draft without session id
if [[ -z "$session_id" ]]; then
  exit 0
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
draft="$repo_root/.claude/checkpoint-draft-$session_id.md"

# Check draft age and remind if stale
if [[ -f "$draft" ]]; then
  # Draft exists - check if stale (older than 30 minutes)
  if [[ "$(uname)" == "Darwin" ]]; then
    draft_age=$(($(date +%s) - $(stat -f %m "$draft")))
  else
    draft_age=$(($(date +%s) - $(stat -c %Y "$draft")))
  fi

  if ((draft_age > 1800)); then
    # Draft is stale (30+ minutes old)
    minutes=$((draft_age / 60))
    printf '{"systemMessage": "Checkpoint draft is %d minutes old. Consider updating it with current session state using the precompact-session-state skill."}\n' "$minutes"
  fi
else
  # No draft exists - gentle reminder
  printf '{"systemMessage": "No checkpoint draft found for this session. Consider creating one with the precompact-session-state skill to enable context recovery if compaction fails."}\n'
fi
