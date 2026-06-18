---
name: claude-memory-cascade-state
description: "Reference — how Claude's file-memory is wired in this repo (three-tier cascade + clean-split config-home) and the hard-won facts about Claude's file model. Chantier SHIPPED to origin/main (4f24c65d + f744bf52)."
metadata:
  node_type: memory
  type: reference
---

**Shipped.** The three-tier Claude file-memory cascade landed on `origin/main`
(`4f24c65d feat(claude-memory): three-tier memory cascade`, `f744bf52 chore(claude): delete the
config-home wrapper`). Branch `chore/claude-memory-cascade` merged + deleted. Design spec:
`wip/specs/2026-06-18-claude-memory-three-tier-cascade-design.adoc`. This note is the durable
reference for how it works; it is no longer an active chantier.

**The model:**
- Three tiers, each a distinct scope + backing: **worktree** (`<wt>/.claude/memory/`, branch git) →
  **hub** (`<wt>/.claude/hub/memory/`, git subtree ↔ `claude-hub`) → **home** (`~/.claude/memory/`,
  minimal = profile only).
- Reading = root index `<wt>/.claude/memory/MEMORY.md` + scoped links `[[hub:name]]` / `[[home:name]]`.
  Location of a note IS its scope.

**Config-home wiring (clean-split — the CURRENT model):** config-home is set PER-WORKSPACE in the
`.code-workspace`, NOT via a global wrapper (the old `claude-config-home-wrapper.sh` was deleted).
`claudeCode.claudeProcessWrapper: ""` (empty=falsy=disabled) + `claudeCode.environmentVariables:
[{name:CLAUDE_CONFIG_DIR, value:<abs>/.claude}]`. So config-home = `<worktree>/.claude` (NOT
`.claude/hub`). Env vars are injected verbatim (`String(value)`, NO `${workspaceFolder}` substitution
→ path must be absolute; fine since host-specific + known at generation). Effects: runtime
(`projects/`, `sessions/`, …) lands under `.claude` (gitignored via `.claude/.gitignore`);
`.claude/hub` is SINGLE-role (subtree content only); memory symlink at
`.claude/projects/<slug>/memory -> .claude/memory`.

**Why the memory symlink is still needed** (despite the subtree being on the FS): the subtree gives
`.claude/hub/memory/` (tier-2), but Claude auto-loads `.claude/projects/<slug>/memory/` — a DIFFERENT
dir, gitignored + slug-keyed, NOT provided by the subtree. The symlink bridges that runtime path →
`<wt>/.claude/memory` (tier-1, tracked, so it rides into main at merge).

**The 5 hard-won facts about Claude's file model (why it was hard):**
1. *Slug encodes `/` AND `.` → `-`.* `rke2lab.d` → `rke2lab-d`. A `s:/:-:g` (only `/`) gives the wrong
   slug → memory "lost" on reload. Correct: `s:[/.]:-:g`. See `link-memory.sh`.
2. *Config-home redirects everything.* Whatever sets `CLAUDE_CONFIG_DIR` decides where Claude reads
   conversations + memory. Pre-clean-split that was the wrapper → `.claude/hub`; now it's the
   `.code-workspace` env var → `.claude`.
3. *Slug = working-tree root (`git rev-parse --show-toplevel`), NOT git-dir.* Per-worktree (isolates
   conversations) — keep it. `--git-dir` was tried and abandoned; `--git-common-dir` would pool the
   whole repo (unwanted).
4. *Two concerns share `.claude`:* config-home runtime (`projects/`, `settings.json`, `plugins/` —
   gitignored) + subtree content under `.claude/hub` (`memory/`, `skills/`, `instructions.md` —
   versioned). The `.gitignore` files separate them.
5. *Conversations are EPHEMERAL + per-worktree.* They live under `<wt>/.claude/projects/<slug>/`;
   removing the worktree deletes them. Committed memory is the ONLY durable cross-boundary carrier.
   The `closed - …` sidebar noise = orphan labels in VSCode `state.vscdb` (workspaceStorage,
   `agentSessions.model.cache`) that SURVIVE worktree removal → purge is the corollary of removal.

**★ Bridging is human — one conversation ↔ one workspace ↔ one worktree.** Claude NEVER links/grafts
a conversation across worktrees (a one-file transcript symlink was tried 2026-06-18 and reverted — it
broke the invariant). Cross-conversation continuity flows through COMMITTED MEMORY; the HUMAN carries
the live thread between windows. A conversation is visible ONLY in its own workspace.

**Residual / not done:** the nix `claudeCodeSeedMemory` activation (minimal home-tier seed-if-absent)
was never wired — revisit only if a fresh machine needs the home tier bootstrapped.

See [[hub:claude-auto-memory-mechanics]] [[hub:external-worktree-operating-model-state]].
