#!/usr/bin/env bash
# PreCompact hook: snapshot the session before context compaction.
#
# Writes .claude/checkpoint-<session_id>.md. The filename is keyed by the
# conversation's session id, so re-compacting the same conversation overwrites
# its checkpoint (keep-last-1) and a checkpoint can be correlated back to its
# transcript for garbage collection (see checkpoint-gc.sh).
#
# Non-destructive by design: this runs *before* compaction (which may be
# blocked or fail), so it only ever writes — never deletes.
set -euo pipefail

input="$(cat)"
session_id="$(printf '%s' "$input" | yq -p json '.session_id // ""')"

# Without a session id we cannot key (or later GC) the checkpoint. Fall back to
# a timestamp so the snapshot is never silently lost.
if [[ -z "$session_id" ]]; then
  session_id="unkeyed-$(date '+%Y-%m-%d-%H%M')"
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
checkpoint="$repo_root/.claude/checkpoint-$session_id.md"
now="$(date '+%Y-%m-%d, %Hh%M')"

{
  echo "# Checkpoint — $now"
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

printf '{"systemMessage": "Checkpoint created: %s"}\n' ".claude/checkpoint-$session_id.md"
