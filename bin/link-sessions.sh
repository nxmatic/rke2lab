#!/usr/bin/env bash
# Bridge this worktree's Claude SESSION history into the home config dir, so the
# Dock-launched VSCode extension host lists the worktree's sessions.
#
# The extension host reads sessions from $HOME/.claude/projects/<slug>/ — its own
# $HOME/.claude, NOT the workspace-scoped CLAUDE_CONFIG_DIR (Dock/Finder launch has
# no shell env). The canonical external-worktree wiring keeps a worktree's
# transcripts IN the worktree and points the home slug at them:
#
#   ~/.claude/projects/<slug>  ->(symlink)->  <worktree>/.claude/projects/<slug>
#
# The symlink target is absolute, so it does NOT survive `git worktree add` — only
# tracked content does. Run this once per worktree to (re)create the bridge.
#
# NOTE: this is the SESSION bridge only. Memory is separate — it is tracked in
# <worktree>/.claude/memory and read from the repo, not bridged here.
#
# Idempotent; refuses to clobber a real, non-empty home dir (existing transcripts).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null || (cd "$script_dir/../.." && pwd))"

# Slug: replace BOTH / and . with - (Claude's derivation).
slug="$(printf '%s' "$repo_root" | sed 's:[/.]:-:g')"

wt_sessions="$repo_root/.claude/projects/$slug"
# Anchor at $HOME/.claude on purpose: the bridge exists FOR the Dock-launched
# extension host, which reads its own $HOME/.claude (no shell env) — NOT the
# workspace CLAUDE_CONFIG_DIR. Following CLAUDE_CONFIG_DIR here would place the
# link in the CREATING window's config dir (it always has one set), where the
# Dock sidebar never looks. See the header note.
config_dir="$HOME/.claude"
home_link="$config_dir/projects/$slug"

mkdir -p "$wt_sessions"                 # so the symlink target exists for a fresh worktree
mkdir -p "$(dirname "$home_link")"

if [[ -L "$home_link" ]]; then
  current="$(readlink "$home_link")"
  if [[ "$current" == "$wt_sessions" ]]; then
    echo "ok: session bridge already points at $wt_sessions"
    exit 0
  fi
  echo "updating existing symlink ($current -> $wt_sessions)"
  rm "$home_link"
elif [[ -d "$home_link" ]]; then
  if [[ -n "$(ls -A "$home_link" 2>/dev/null)" ]]; then
    echo "warning: $home_link is a real non-empty dir (existing transcripts)." >&2
    echo "Move its contents into $wt_sessions, then re-run. Aborting." >&2
    exit 1
  fi
  rmdir "$home_link"
elif [[ -e "$home_link" ]]; then
  echo "warning: $home_link exists and is neither a symlink nor a dir. Aborting." >&2
  exit 1
fi

ln -s "$wt_sessions" "$home_link"
echo "linked: $home_link -> $wt_sessions"
