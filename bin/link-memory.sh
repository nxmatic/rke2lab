#!/usr/bin/env bash
# Recreate the config-dir -> repo symlink for Claude's memory store.
#
# Claude reads/writes memory at <config>/projects/<repo-slug>/memory, where
# <config> = ${CLAUDE_CONFIG_DIR:-$HOME/.claude}. The real, git-tracked files live
# in this repo at .claude/memory/. The bridging symlink uses an absolute target,
# so it does NOT survive `git clone`/`worktree add` — only the content does. Run
# this once after cloning (or whenever the link is missing) to restore the bridge.
#
# Idempotent and safe: refuses to clobber a real (non-symlink) memory dir.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"   # .claude/bin -> repo root
repo_memory="$repo_root/.claude/memory"

if [[ ! -d "$repo_memory" ]]; then
  echo "error: $repo_memory does not exist — nothing to link to." >&2
  exit 1
fi

config_dir="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
# Slug: replace BOTH / and . with - (Claude's derivation).
slug="$(printf '%s' "$repo_root" | sed 's:[/.]:-:g')"
home_link="$config_dir/projects/$slug/memory"

mkdir -p "$(dirname "$home_link")"

if [[ -L "$home_link" ]]; then
  current="$(readlink "$home_link")"
  if [[ "$current" == "$repo_memory" ]]; then
    echo "ok: symlink already points at $repo_memory"
    exit 0
  fi
  echo "updating existing symlink ($current -> $repo_memory)"
  rm "$home_link"
elif [[ -e "$home_link" ]]; then
  echo "warning: $home_link exists and is not a symlink." >&2
  echo "Back it up and merge into $repo_memory, then re-run. Aborting." >&2
  exit 1
fi

ln -s "$repo_memory" "$home_link"
echo "linked: $home_link -> $repo_memory"
