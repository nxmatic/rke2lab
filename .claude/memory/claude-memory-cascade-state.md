---
name: claude-memory-cascade-state
description: "Chantier state — three-tier Claude memory cascade (worktree→hub→home); brainstorm frozen, spec written, ready for writing-plans"
metadata:
  node_type: memory
  type: project
---

**Chantier:** restructure Claude file-memory into a three-tier cascade. Branch/worktree
`chore/claude-memory-cascade`. Brainstorm FROZEN 2026-06-18; spec written + committed; next step is
`writing-plans` then implementation. Spec: `wip/specs/2026-06-18-claude-memory-three-tier-cascade-design.adoc`.

**The model (decided):**
- Three tiers, each a distinct scope + backing: **worktree** (`<wt>/.claude/memory/`, branch git) →
  **hub** (`<wt>/.claude/hub/memory/`, git subtree ↔ `claude-hub`) → **home** (`~/.claude/memory/`,
  nix seed-if-absent, minimal = profile only).
- Reading = **Option B**: root index `<wt>/.claude/memory/MEMORY.md` + scoped links `[[hub:name]]`
  / `[[home:name]]`. Location of a note IS its scope.

**The 5 hard-won facts about Claude's file model (the why-it-was-hard):**
1. *Slug encodes `/` AND `.` → `-`.* `rke2lab.d` → `rke2lab-d`. `link-memory.sh` did `s:/:-:g`
   (only `/`) → wrong slug → memory "lost" on reload. Fix: `s:[/.]:-:g`. This hit EVERY worktree.
2. *The wrapper redirects everything.* `claudeProcessWrapper` (VSCode user-settings, fixed path to
   `claude-hub.d/main/.claude/bin/claude-config-home-wrapper.sh`) sets
   `CLAUDE_CONFIG_DIR=$(git rev-parse --show-toplevel)/.claude/hub`. So Claude reads conversations +
   memory under `<wt>/.claude/hub/projects/<slug>/`, NOT `~/.claude/`.
3. *Slug = working-tree root (`--show-toplevel`), NOT git-dir.* The wrapper LREADS `$PWD`, never `cd`.
   `--show-toplevel` is correct and per-worktree (isolates conversations) — keep it. `--git-dir` was
   tried and abandoned. `--git-common-dir` would pool the whole repo (unwanted).
4. *`.claude/hub` has TWO roles* fused in one dir: config-home (runtime: `projects/`, `settings.json`,
   `plugins/` — gitignored) + subtree content (`memory/`, `skills/`, `instructions.md` — versioned).
   The hub `.gitignore` separates them. Deliberate trade (shared settings free, runtime pollutes
   subtree). Cleaner split deferred.
5. *Conversations are EPHEMERAL + per-worktree.* They live in `<wt>/.claude/hub/projects/<slug>/`;
   removing the worktree deletes them. Memory (committed) is the ONLY durable cross-boundary carrier.
   The `closed - …` sidebar noise = orphan labels in VSCode `state.vscdb` (workspaceStorage,
   `agentSessions.model.cache`) that SURVIVE worktree removal → purge is the corollary of removal.

**★ CLEAN-SPLIT (2026-06-18, supersedes the wrapper):** config-home is now set PER-WORKSPACE in the
`.code-workspace`, not via the global wrapper. `claudeCode.claudeProcessWrapper: ""` (empty=falsy=
disabled for this window) + `claudeCode.environmentVariables: [{name:CLAUDE_CONFIG_DIR, value:
<abs>/.claude}]`. So config-home = `<worktree>/.claude` (NOT `.claude/hub`). Verified against the
extension bundle: wrapper gated by `if(e)`; env vars injected verbatim (`String(value)`, NO
`${workspaceFolder}` substitution → path must be absolute, fine since host-specific + known at
generation). Effects: runtime (`projects/`, `sessions/`, …) lands under `.claude` → added to
`.claude/.gitignore`; `.claude/hub` reverts to SINGLE role (subtree content only); memory symlink
now at `.claude/projects/<slug>/memory -> .claude/memory` (the old `.claude/hub/projects/...` one was
removed). The `.code-workspace` is self-defined: depends only on the worktree folder.

**Why the memory symlink is still needed** (despite the subtree being on the FS): the subtree gives
`.claude/hub/memory/` (tier-2), but Claude auto-loads `.claude/hub/projects/<slug>/memory/` — a
DIFFERENT dir, gitignored + slug-keyed, NOT provided by the subtree. The symlink bridges that runtime
path → `<wt>/.claude/memory` (tier-1, tracked, so it rides into main at merge).

**Status of the work:** content migration already DONE (0 doublons; hub=34 cross-cutting,
worktree=37 project-specific). Remaining = WIRING + hygiene only: (a) fix `link-memory.sh` on two
axes (slug encoding + anchor under `$CLAUDE_CONFIG_DIR`); (b) dedup the 3 copies of `link-memory.sh`
→ canonical in hub `bin/`; (c) per-worktree slug symlink; (d) nix `claudeCodeSeedMemory` activation
(minimal home seed). Lifecycle + sidebar-purge mechanism = "validate in real life", not hardened yet.

**Lifecycle (target):** main conversation brainstorms → commit handoff to memory → create worktree +
`.code-workspace` → hand back a startup prompt → work in worktree → save/commit → back in main, say
done → merge/squash (branch tier-1 memory rides into main).

**★ Bridging is human — one conversation ↔ one workspace ↔ one worktree.** Claude NEVER links/grafts
a conversation across worktrees (we tried a one-file transcript symlink into another worktree's
config-home, 2026-06-18, and reverted it — it broke the invariant). Cross-conversation continuity
flows through COMMITTED MEMORY (durable channel); the HUMAN carries the live thread between windows.
A conversation is visible ONLY in its own workspace. Simpler + more robust: no dangling links, sharp
boundary.

See [[hub:claude-auto-memory-mechanics]] [[hub:external-worktree-operating-model-state]].
