#!/usr/bin/env bash
# PostCompact hook: garbage-collect orphaned checkpoints.
#
# A checkpoint is named checkpoint-<session_id>.md. Its conversation transcript
# lives at <projects-dir>/<session_id>.jsonl. When a conversation is deleted,
# its transcript disappears — but no hook fires on deletion, so we reclaim the
# orphan here: any checkpoint whose transcript is gone gets removed.
#
# Runs in PostCompact (after compaction completes) so destructive work never
# happens ahead of a compaction that might be blocked or fail. Claude Code
# documents PostCompact as intended for "side effects like logging or cleanup".
set -euo pipefail

input="$(cat)"
transcript_path="$(printf '%s' "$input" | yq -p json '.transcript_path // ""')"

# The projects dir (holding every <session_id>.jsonl for this project) is the
# directory containing this session's own transcript.
if [[ -z "$transcript_path" ]]; then
  exit 0  # Can't locate transcripts; do nothing rather than guess.
fi
projects_dir="$(dirname "$transcript_path")"

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
removed=0

shopt -s nullglob
for checkpoint in "$repo_root"/.claude/checkpoint-*.md; do
  base="$(basename "$checkpoint" .md)"
  session_id="${base#checkpoint-}"

  # Leave timestamp-fallback checkpoints (unkeyed-*) alone — they have no
  # transcript to correlate against, so we can't safely judge them orphaned.
  case "$session_id" in
    unkeyed-*) continue ;;
  esac

  if [[ ! -f "$projects_dir/$session_id.jsonl" ]]; then
    rm -f "$checkpoint"
    removed=$((removed + 1))
  fi
done

if (( removed > 0 )); then
  printf '{"systemMessage": "Pruned %d orphaned checkpoint(s)."}\n' "$removed"
fi
