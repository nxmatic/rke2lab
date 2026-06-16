---
name: claude-distributed-assets-topic
description: "POSTPONED topic — sharing Claude config/memory/skills across repos and Darwin hosts; too complex, own branch later"
metadata:
  type: project
---

POSTPONED (2026-06-06): how to handle Claude's distributed assets across my filesystem —
sharing the global linking/memory logic across repositories AND across my owned Darwin hosts.
Deemed too complex to address mid-flight; gets its own branch later.

**What already exists (DONE, keep as-is — do NOT redo):**
- rke2lab `refactor/config`: memory store tracked in-repo at `.claude/memory/`, home path
  `~/.claude/projects/-private-var-lib-git-nxmatic-rke2lab/memory` symlinked to it
  (commit `967388bc`). This WORKS and survived a window reload — leave it.
- rke2lab `.claude/bin/link-memory.sh` (commit `a79c8fd2`): per-repo helper to recreate the
  home→repo symlink on a fresh clone (symlink target is absolute, doesn't survive cloning).
- Personal skill `~/.claude/skills/track-claude-memory-in-repo/` — the manual setup procedure
  (move memory into repo, gitignore/visibility check, commit). Kept.

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

**Open design questions for the branch:** dedup overlap between the nix module (linking) and
the personal skill (initial setup) — they should be non-overlapping (Nix owns linking, skill
owns the one-time move+commit). Also: the `a79c8fd2` per-repo helper becomes redundant with the
nix module on nix-managed hosts but is the fallback on non-nix hosts.
