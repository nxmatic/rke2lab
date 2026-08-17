---
name: track-claude-memory-in-repo
description: Use when the user wants Claude's memory persisted/version-controlled in a git repo so it survives window reloads, compaction, or machine changes. Points Claude's auto-memory at the repo's tracked .claude/memory/ via the native autoMemoryDirectory setting (absolute path in .claude/settings.local.json), then commits memory/ only. Triggers on phrasing like "track memory in git", "persist memory in the repo", "version-control my memory", "I lost my memory on reload", or wanting the same memory setup applied to another repository.
tools: Bash, Read, Edit, Write, AskUserQuestion
---

# Track Claude memory in a git repo

Claude's auto-memory (the `MEMORY.md` index + topic files) defaults to
`~/.claude/projects/<repo-slug>/memory/` — outside git, lost on reload, and
**repo-wide** (all worktrees of a repo share one dir, keyed off the git
repository, not the worktree). This skill relocates it into the repo's tracked
`<repo>/.claude/memory/` so git version-controls it and it rides along at merge.

## Mechanism: the native `autoMemoryDirectory` setting

`autoMemoryDirectory` (Claude Code settings) redirects **both reading and
writing** of auto-memory to a directory you name. It accepts an **absolute path
or a `~/`-prefixed path** (no relative paths) and is honored from
`.claude/settings.json` or `.claude/settings.local.json` under the same
workspace-trust rule as hooks. Point it at the repo's tracked memory dir and
Claude writes straight there — **no symlink, no slug computation, no home-dir
bridge.** Verified working on darwin (2026-08-14).

Because the path must be absolute, it is host- and worktree-specific → it lives
in the gitignored, per-checkout **`.claude/settings.local.json`** (the same file
that carries the Bedrock env), not the committed `settings.json`. In the
external-worktree model the `worktree` skill writes this line at worktree
creation, so a fresh checkout is wired automatically; this skill is the manual
once-per-repo setup for repos that don't use that flow.

## Scope: memory/ ONLY — never the transcripts

Session transcripts (`*.jsonl`, `tool-results/`) live under
`~/.claude/projects/<slug>/` (or the `CLAUDE_CONFIG_DIR` equivalent) and are NOT
memory — often tens of MB churning every session. `autoMemoryDirectory` moves
**only** the memory dir; transcripts stay where they are (surface them in the
Dock sidebar with `link-sessions.sh` if needed — a separate concern). Track only
`<repo>/.claude/memory/`.

## Checklist

Create a TodoWrite item per step and do them in order.

1. **Confirm visibility (BLOCKING for public repos).** `git remote -v`; if
   `origin` is public, use AskUserQuestion to confirm the user accepts that
   memory notes (provisioning state, working-style prefs, design decisions)
   become publicly visible. Do not proceed on a public repo without it.

2. **gitignore check.** `git check-ignore -v .claude/memory/probe.md` — exit 1 /
   no output means not ignored (good). If ignored, surface the rule and resolve
   before continuing; the whole point is that git tracks this dir.

3. **Ensure the dir exists.** `mkdir -p .claude/memory`. If migrating from the
   old default location, copy existing notes in first:
   `cp -a "$HOME/.claude/projects/$(git rev-parse --show-toplevel | sed 's:[/.]:-:g')/memory/." .claude/memory/`
   (skip if there's nothing to migrate).

4. **Set `autoMemoryDirectory`** to the **absolute** repo memory path in
   `.claude/settings.local.json` (create the file or merge the key into it):
   ```json
   { "autoMemoryDirectory": "<abs-repo-root>/.claude/memory" }
   ```
   Derive the absolute root with `pwd` / `git rev-parse --show-toplevel`; do not
   hardcode. `settings.local.json` is gitignored — the setting is not committed;
   the **content** in `.claude/memory/` is what git tracks.

5. **Reload + verify the write path.** The setting is read at session start, so
   reload the window. Then confirm a memory write lands in the tracked dir:
   ask Claude to remember a throwaway marker, then
   `git status --short .claude/memory/` must show the change (and no `memory/`
   dir appears at the old `projects/<slug>/memory` default). Revert the marker
   once confirmed.

6. **Commit `.claude/memory/` ONLY.** Dry-run `git add -n .claude/memory/ |
   grep -iE 'jsonl|tool-results'` must be empty. Stage and commit `.claude/memory/`;
   do not push unless the user asks.

## Anti-patterns

- ❌ Symlinking `~/.claude/projects/<slug>/memory` → repo (the old hack:
   slug-fragile, reader-dependent, broke on non-main worktrees). `autoMemoryDirectory`
   replaces it — an absolute path is reader- and slug-independent.
- ❌ A relative or `${workspaceFolder}` path in `autoMemoryDirectory` — only
   absolute or `~/` are accepted.
- ❌ Putting `autoMemoryDirectory` in the committed `settings.json` — the path is
   host-specific; it belongs in per-checkout `settings.local.json`.
- ❌ Tracking `projects/<slug>/` — drags in churning transcripts.
- ❌ Pushing to a public repo without the step-1 visibility confirmation.

## Applying to a new repository

Once-per-repo: run the checklist. In the external-worktree family this is
automatic — the `worktree` skill writes `autoMemoryDirectory` into each new
checkout's `settings.local.json` at creation, and the tracked `.claude/memory/`
content rides along in git.
