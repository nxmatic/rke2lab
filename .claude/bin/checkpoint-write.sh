#!/usr/bin/env bash
# PreCompact hook: snapshot the session before context compaction.
#
# Merges agent-authored session context (if present) with git state.
# - Agent draft: .claude/checkpoint-draft-<session_id>.md (conversation context)
# - Git state: commits, status (repo state)
# - Output: .claude/checkpoint-<session_id>-<timestamp>.md (complete recovery point)
#
# Non-destructive by design: runs *before* compaction (which may fail),
# so only writes — never deletes.
set -euo pipefail

input="$(cat)"
session_id="$(printf '%s' "$input" | yq -p json '.session_id // ""')"

# Without a session id we cannot key (or later GC) the checkpoint. Fall back to
# a timestamp so the snapshot is never silently lost.
if [[ -z "$session_id" ]]; then
  session_id="unkeyed-$(date '+%Y%m%d-%H%M%S')"
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
timestamp="$(date '+%Y%m%d-%H%M%S')"
checkpoint="$repo_root/.claude/checkpoint-$session_id-$timestamp.md"
draft="$repo_root/.claude/checkpoint-draft-$session_id.md"
now_display="$(date '+%Y-%m-%d, %Hh%M')"

# Check for agent-authored draft
if [[ -f "$draft" ]]; then
  # Merge strategy: agent context + git state
  {
    echo "# Checkpoint — $now_display"
    echo ""
    echo "> Auto-generated before compaction. Session \`$session_id\`. Resume with this file."
    echo ""
    echo "---"
    echo ""
    # Include agent-authored context (strip any leading "# Session Context" header)
    sed '1{/^# Session Context$/d;}' "$draft"
    echo ""
    echo "---"
    echo ""
    echo "## Git State"
    echo ""
    echo "### Recent commits (last 5)"
    echo ""
    git -C "$repo_root" log --oneline -5 2>/dev/null || echo "(no git history)"
    echo ""
    echo "### Working tree status"
    echo ""
    git -C "$repo_root" status --short 2>/dev/null | head -20 || echo "(no changes)"
  } > "$checkpoint"

  # Clean up draft after successful merge
  rm -f "$draft"

  printf '{"systemMessage": "Checkpoint created with agent context: %s"}\n' \
    ".claude/checkpoint-$session_id-$timestamp.md"
else
  # Fallback: git state only (no agent draft found)
  {
    echo "# Checkpoint — $now_display"
    echo ""
    echo "> Auto-generated before compaction. Session \`$session_id\`. Resume with this file."
    echo ""
    echo "## Recent commits (last 5)"
    echo ""
    git -C "$repo_root" log --oneline -5 2>/dev/null || echo "(no git history)"
    echo ""
    echo "## Current state"
    echo ""
    git -C "$repo_root" status --short 2>/dev/null | head -20 || echo "(no changes)"
    echo ""
    echo "## Next steps"
    echo ""
    echo "- Review blockers/errors above"
    echo "- Continue from last commit's goal"
  } > "$checkpoint"

  printf '{"systemMessage": "Checkpoint created (git state only): %s"}\n' \
    ".claude/checkpoint-$session_id-$timestamp.md"
fi
