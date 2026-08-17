#!/usr/bin/env bash
# Surface shared hub skills into the project skills dir so Claude Code discovers them.
#
# Claude Code auto-discovers skills only under <project>/.claude/skills/ (and the
# user-scope skills dir) — NOT under the hub subtree at .claude/hub/skills/. But it
# DOES follow symlinks, so we link each hub skill into .claude/skills/. The link
# target is RELATIVE (../hub/skills/<name>), so it is committable and survives across
# machines; the skill content lives once, in the subtree.
#
# Idempotent and safe: skips a correct link, refreshes a stale one, refuses to
# clobber a real (non-symlink) entry. Run once after adding/refreshing the hub
# subtree, or whenever a hub skill is missing from .claude/skills/.
set -euo pipefail

# Resolve the repo root from the script's location. The script ships at two nesting
# depths — `.claude/bin/` in the claude-hub repo, `.claude/hub/bin/` in a subtree
# consumer — so ask git for the toplevel (correct at any depth); fall back to a
# relative climb only when git is unavailable.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null || (cd "$script_dir/../.." && pwd))"
hub_skills="$repo_root/.claude/hub/skills"
proj_skills="$repo_root/.claude/skills"

if [[ ! -d "$hub_skills" ]]; then
  echo "error: $hub_skills does not exist — nothing to surface." >&2
  exit 1
fi

mkdir -p "$proj_skills"

for skill in "$hub_skills"/*/; do
  [[ -d "$skill" ]] || continue
  name="$(basename "$skill")"
  link="$proj_skills/$name"
  target="../hub/skills/$name"   # relative to proj_skills

  if [[ -L "$link" ]]; then
    if [[ "$(readlink "$link")" == "$target" ]]; then
      echo "ok: .claude/skills/$name already surfaced"
      continue
    fi
    echo "updating stale link .claude/skills/$name"
    rm "$link"
  elif [[ -e "$link" ]]; then
    echo "warning: $link exists and is not a symlink — skipping (resolve by hand)." >&2
    continue
  fi

  ln -s "$target" "$link"
  echo "linked: .claude/skills/$name -> $target"
done
