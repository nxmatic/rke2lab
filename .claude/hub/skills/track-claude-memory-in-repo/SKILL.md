---
name: track-claude-memory-in-repo
description: Use when the user wants Claude's memory persisted/version-controlled in a git repo so it survives window reloads, compaction, or machine changes. Moves the home-dir memory/ into the repo's .claude/memory/, symlinks the home path back to it, and commits memory/ only (never the session transcripts). Triggers on phrasing like "track memory in git", "persist memory in the repo", "symlink the memory folder", "I lost my memory on reload", or wanting the same memory setup applied to another repository.
tools: Bash, Read, Edit, AskUserQuestion
---

# Track Claude memory in a git repo

Claude's distilled memory lives at `~/.claude/projects/<repo-slug>/memory/`. It is lost on
window reloads and is not version-controlled. This skill makes the **real files live in the
repo** (`<repo>/.claude/memory/`) and turns the home path into a **symlink** pointing at them,
so the memory system keeps its usual read/write path while git tracks the content.

## Why this direction (real files in repo, symlink at home)

A committed symlink stores only its *target path string* — git does NOT follow it to track
content. So the real files MUST live in the repo for git to track them; the home path becomes
the symlink. Do not reverse this.

## Scope: memory/ ONLY

The `~/.claude/projects/<slug>/` directory also holds raw session transcripts (`*.jsonl` +
`tool-results/` subdirs) — often tens of MB that churn every session. NEVER track those. Track
only the `memory/` subdir (distilled facts, typically <100KB).

## Checklist

Create a TodoWrite item per step and do them in order.

1. **Locate the source.** Find `~/.claude/projects/<slug>/memory`. The `<slug>` is the repo's
   absolute path with `/` replaced by `-` (e.g. `/private/var/lib/git/nxmatic/rke2lab` →
   `-private-var-lib-git-nxmatic-rke2lab`). Derive it from the repo root, don't guess:
   `SLUG=$(pwd | sed 's:/:-:g')`. If no `memory/` exists yet, tell the user there's nothing to
   track and stop.

2. **Confirm visibility (BLOCKING for public repos).** Run `git remote -v` and check whether
   `origin` is public. If public, use AskUserQuestion to confirm the user accepts that memory
   notes (which may include provisioning state, working-style prefs, design decisions) become
   publicly visible. Do not proceed on a public repo without explicit confirmation.

3. **gitignore check.** `git check-ignore -v .claude/memory/probe.md` — exit 1 / no output
   means not ignored (good). If ignored, surface the rule and resolve before continuing.

4. **Copy + verify identical.** `mkdir -p .claude/memory && cp -a "$SRC"/. .claude/memory/`,
   then `diff -r "$SRC" .claude/memory && echo IDENTICAL`. Do not proceed unless identical.

5. **Backup + symlink.** `mv "$SRC" "$SRC.bak"; ln -s "$(pwd)/.claude/memory" "$SRC"`. Verify
   with `ls -ld "$SRC"`.

6. **Roundtrip test.** Append a marker to `"$SRC/MEMORY.md"` (the home path), confirm
   `git status --short .claude/memory/MEMORY.md` shows it as a repo change, then revert with
   `git checkout -- .claude/memory/MEMORY.md`. This proves the symlink resolves into git.

7. **Drop the portable helper.** Copy `link-memory.sh` (next to this SKILL.md) into the repo's
   `.claude/bin/`. It recreates the home→repo symlink on a fresh clone (the symlink target is
   absolute, so it does not survive cloning to a new machine — only the content does). Ensure
   `.claude/.gitignore` does not exclude it (repos often ignore `[Bb]in/`; add `!bin/` +
   `!bin/*.sh` if so).

8. **Remove backup + commit.** `diff -r "$SRC.bak" .claude/memory` to confirm nothing lost,
   then `rm -rf "$SRC.bak"`. Stage `.claude/memory/` (+ the helper script) ONLY — dry-run
   `git add -n .claude/memory/ | grep -iE 'jsonl|tool-results'` must be empty. Commit; do not
   push unless the user asks.

## Anti-patterns

- ❌ Symlinking the *whole* `projects/<slug>/` dir — drags in transcripts (huge, churning).
- ❌ Committing a symlink and expecting git to track the target's content — it won't.
- ❌ Pushing to a public repo without the visibility confirmation in step 2.
- ❌ Skipping the copy-verify / roundtrip / backup-compare safety checks.

## Applying to a new repository

This is a once-per-repo setup. Run the full checklist in each repo where durable memory is
wanted. The helper script (step 7) handles re-linking after a clone; the content itself rides
along in git.
