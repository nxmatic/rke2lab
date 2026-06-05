#!/usr/bin/env bash
# PostCompact hook: garbage-collect old checkpoints per session.
#
# Strategy:
# 1. Remove orphaned checkpoints (session deleted)
# 2. For active sessions, keep only the N most recent checkpoints per session
#
# A checkpoint is named checkpoint-<session_id>-<timestamp>.md. Its conversation
# transcript lives at <projects-dir>/<session_id>.jsonl. When a conversation is
# deleted, its transcript disappears — but no hook fires on deletion, so we
# reclaim the orphan here.
#
# Runs in PostCompact (after compaction completes) so destructive work never
# happens ahead of a compaction that might be blocked or fail.
set -euo pipefail

# Configuration: keep N most recent checkpoints per session
KEEP_LAST_N="${CLAUDE_CHECKPOINT_KEEP_LAST:-3}"

input="$(cat)"
transcript_path="$(printf '%s' "$input" | yq -p json '.transcript_path // ""')"

# The projects dir (holding every <session_id>.jsonl for this project) is the
# directory containing this session's own transcript.
if [[ -z "$transcript_path" ]]; then
  exit 0  # Can't locate transcripts; do nothing rather than guess.
fi
projects_dir="$(dirname "$transcript_path")"

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
removed_orphans=0
removed_old=0

# First pass: collect all checkpoints by session
declare -A session_checkpoints
shopt -s nullglob
for checkpoint in "$repo_root"/.claude/checkpoint-*.md; do
  base="$(basename "$checkpoint" .md)"
  # Extract session_id from checkpoint-<session_id>-<timestamp>.md
  # Timestamp format is YYYYMMDD-HHMMSS (always 15 chars with dash)
  # So remove last 16 chars: "-YYYYMMDD-HHMMSS"
  session_id="${base#checkpoint-}"  # Remove "checkpoint-" prefix
  session_id="${session_id%-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]}"  # Remove "-YYYYMMDD-HHMMSS" suffix

  # Skip unkeyed checkpoints (never auto-GC these)
  case "$session_id" in
    unkeyed-*) continue ;;
  esac

  # Collect all checkpoints for this session
  if [[ -z "${session_checkpoints[$session_id]:-}" ]]; then
    session_checkpoints[$session_id]="$checkpoint"
  else
    session_checkpoints[$session_id]="${session_checkpoints[$session_id]} $checkpoint"
  fi
done

# Process each session
for session_id in "${!session_checkpoints[@]}"; do
  # Check if transcript exists
  if [[ ! -f "$projects_dir/$session_id.jsonl" ]]; then
    # Session deleted - remove ALL its checkpoints
    for checkpoint in ${session_checkpoints[$session_id]}; do
      rm -f "$checkpoint"
      removed_orphans=$((removed_orphans + 1))
    done
  else
    # Active session - keep only N most recent
    # Convert space-separated list to array and sort by mtime
    read -ra checkpoints <<< "${session_checkpoints[$session_id]}"

    # Sort by modification time (newest first)
    mapfile -t sorted < <(ls -t "${checkpoints[@]}" 2>/dev/null)

    # Remove all but the first N
    count=0
    for checkpoint in "${sorted[@]}"; do
      count=$((count + 1))
      if (( count > KEEP_LAST_N )); then
        rm -f "$checkpoint"
        removed_old=$((removed_old + 1))
      fi
    done
  fi
done

# Report results
total_removed=$((removed_orphans + removed_old))
if (( total_removed > 0 )); then
  msg="Pruned $total_removed checkpoint(s)"
  if (( removed_orphans > 0 )); then
    msg="$msg ($removed_orphans orphaned"
  fi
  if (( removed_old > 0 )); then
    if (( removed_orphans > 0 )); then
      msg="$msg, $removed_old old)"
    else
      msg="$msg ($removed_old old)"
    fi
  else
    if (( removed_orphans > 0 )); then
      msg="$msg)"
    fi
  fi
  printf '{"systemMessage": "%s"}\n' "$msg"
fi
