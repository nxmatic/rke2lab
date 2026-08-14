---
name: claude-distributed-assets-topic
description: "POSTPONED topic — sharing Claude config/memory/skills across repos and Darwin hosts; too complex, own branch later"
metadata:
  type: project
---

POSTPONED (2026-06-06): how to handle Claude's distributed assets across my filesystem —
sharing the global linking/memory logic across repositories AND across my owned Darwin hosts.
Deemed too complex to address mid-flight; gets its own branch later.

**★ PARADIGM SHIFT (2026-08-14) — reframe before resuming this branch.** The symlink-based
memory bridge is GONE: `autoMemoryDirectory` (native setting, absolute/~ path in
`settings.local.json`, VERIFIED on darwin — see [[claude-auto-memory-mechanics]]) redirects
auto-memory read+write straight into the tracked `<repo>/.claude/memory`. No symlink, no slug,
no home-dir bridge. So the nix scan-and-link design below (symlink every `<repo>/.claude/memory`
into the home path) is largely MOOT — the per-repo replacement is just writing one absolute
`autoMemoryDirectory` line per checkout (the `worktree` skill already does this). The real
remaining cross-host question is only how to seed/share that setting + the tracked content,
not symlink plumbing.

**What already exists (current reality):**
- rke2lab: memory store tracked in-repo at `.claude/memory/`. The home→repo **symlink** and its
  helper `link-memory.sh` are **DELETED** — superseded by `autoMemoryDirectory`.
- Personal skill `track-claude-memory-in-repo` — REWRITTEN around `autoMemoryDirectory` (set the
  absolute path in `settings.local.json`, gitignore/visibility check, commit `.claude/memory/`).

**What was REVERTED (the postponed part):**
- nix-darwin-home `modules/home-manager/claude-code.nix` was extended with `memoryScanRoots` +
  `memoryScanDepth` options + a `claude-code.d/link-memory.sh` activation script that
  scan-and-links every `<repo>/.claude/memory` under configured roots into the home path.
  Tested working (find→link, idempotent, clobber-guard preserves real dirs). Reverted from
  `develop` per operator request — to be reintroduced on the future branch.

**Design already settled for when we resume (operator's stated requirements):**
- Logic shared via nix-darwin-home `claude-code` module → all owned Darwin hosts.
- Per-host OPT-IN (`memoryScanRoots` default `[]` = no-op). A host shares specifics only if
  wanted.
- Activation establishes the BRIDGE only; NEVER writes memory content, NEVER clobbers a real
  (non-symlink) home memory dir — it WARNs and defers to the operator. Reconciling/resetting
  memory on other hosts stays MANUAL so nothing is ever lost.

**Open design questions for the branch:** with `autoMemoryDirectory` replacing symlinks, the nix
module no longer LINKS anything — the question shrinks to seeding/sharing the setting + tracked
content across hosts (the skill owns the one-time move+commit; a host opts in). The old
`a79c8fd2` / `link-memory.sh` per-repo symlink helper is deleted and no longer part of this.
