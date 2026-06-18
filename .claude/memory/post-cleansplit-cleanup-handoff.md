---
name: post-cleansplit-cleanup-handoff
description: "Handoff for the NEXT main-window session after the clean-split reload — a punch-list of known leftover files/branches safe to delete that THIS session could not remove (it was reading from the old .claude/hub config-home). Delete this note once the punch-list is done."
metadata:
  node_type: memory
  type: project
---

**Why this note exists.** On 2026-06-18 the clean-split migration completed: the wrapper was
deleted, `rke2lab.code-workspace` (multi-root) was migrated to `claudeProcessWrapper: ""` +
`CLAUDE_CONFIG_DIR=<repo>.d/main/.claude`, the subtree synced up to the hub (origin `dbf7c52`),
and main's memory was re-wired to the clean-split path. But the session that did this was STILL
running on the OLD config-home (`.claude/hub/projects/...`), so it could not delete the now-orphan
runtime without sawing off the branch it sat on. This is the deferred cleanup — run it in a FRESH
main-window session, AFTER reloading `rke2lab.code-workspace` (which is when the clean-split takes
effect). Continuity here is via memory + human bridge (per [[claude-memory-cascade-state]]), not a
transcript graft.

**Punch-list (all verified safe on 2026-06-18 — gitignored runtime or merged branches):**

1. *Old hub-path config-home runtime (main)* — orphaned once the window reloads on the clean-split:
   `rm -rf /private/var/lib/git/nxmatic/rke2lab.d/main/.claude/hub/projects/`
   (gitignored via `.claude/hub/.gitignore:5 projects/` — pure runtime, nothing tracked; holds the
   old memory symlink + this session's transcripts under the old slug path). Confirm the NEW path
   `/.../main/.claude/projects/<slug>/memory` resolves first (it was wired this session).

2. *Design worktree old hub-path runtime* (if the design window was never reloaded on clean-split):
   `rm -rf /private/var/lib/git/nxmatic/rke2lab.d/design/target-module-layout/.claude/hub/projects/`
   (same gitignored-runtime rationale; the design worktree's clean-split memory is already wired at
   `.../design/target-module-layout/.claude/projects/<slug>/memory`).

3. *Stale merged branch* `refactor/config` — tip `5c667408`, already an ancestor of origin/main:
   `git -C /private/var/lib/git/nxmatic/rke2lab.d/main branch -d refactor/config`.

**Note on the rm permission.** `rm -rf` under `~/.claude` is hard-denied by a permission rule, but
these paths are under the REPO worktree (`rke2lab.d/...`), NOT `~/.claude` — so they should be
deletable directly. If a path under `~/.claude/projects/` ever needs purging, the USER must run it.

**When done:** delete this note and its MEMORY.md index line.

Related: [[claude-memory-cascade-state]] (the clean-split decision + 5 facts),
[[hub:external-worktree-operating-model-state]] (worktree lifecycle + cleanup).
