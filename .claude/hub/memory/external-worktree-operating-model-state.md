---
name: external-worktree-operating-model-state
description: "★ CHANTIER 2026-06-15: adopting external-worktree layout (<repo>.d/<namespace>/<branch>) + version-controlled Claude config in the hub. rke2lab MIGRATED to rke2lab.d/{main,chore,feature}; hub repo RENAMED claude-memory→claude-hub + moved to claude-hub.d/main (GitHub repo renamed too, slug bridge repointed). NEXT: prove CLAUDE_CONFIG_DIR on darwin (hub spike worktree exists), give hub a link-memory.sh. Per-branch MEMORY isolation proven IMPOSSIBLE; hub-subtree DROPPED."
metadata:
  node_type: memory
  type: project
  originSessionId: 8e678ad3-a55c-4557-ad9c-921e83b18f14
---

**★ RESUME POINT (read first). Started as "review my branch in VSCode while keeping
the chat"; became a full operating-model migration.** Context window hit its limit
mid-flight — this note + [[claude-auto-memory-mechanics]] are the handoff.

**★ UPDATE 2026-06-16 — config-home SHIPPED; transcript + memory bridges restored;
hub link-memory.sh FIXED (both repos, pushed).** The wrapper (`claudeProcessWrapper`
in rke2lab.code-workspace → hub/bin/claude-config-home-wrapper.sh) is live: it sets
`CLAUDE_CONFIG_DIR=<worktree>/.claude/hub` for the spawned CLI. KEY GOTCHA proven this
session: **the wrapper only sets the var for its CHILD (the CLI), NOT for the VSCode
EXTENSION HOST that paints the sidebar.** The sidebar's `fetchSessions()` runs IN the
ext host and reads its OWN `process.env.CLAUDE_CONFIG_DIR` (via `Ss()` in extension.js)
— UNSET, because VSCode is Dock/Finder-launched (no shell env). Parent-chain probe:
ext-host depth-3 = unset, CLI depth-2 = set-by-wrapper. So after the config switch the
sidebar read `$HOME/.claude/projects/<slug>` (emptied of flat transcripts by a PRIOR
move that pushed them into the hub) → looked empty, nothing actually lost. FIX (per-slug,
machine-local, in NO repo): `~/.claude/projects/<slug>` → symlink → `hub/projects/<slug>`;
ext host follows it, finds the 65 transcripts. The 27 orphaned sidecar dirs (subagents/
tool-results/workflows) were copied into the hub first. `environmentVariables` setting
can't help — `$d()` copies process.env for the child spawn only, never mutates ext host.
Only var-based alternatives (launchctl setenv / shell-launch VSCode) are GLOBAL → break
per-worktree isolation; the symlink is the per-worktree-correct lever, same pattern as
the memory bridge. Memory bridge also restored: `hub/projects/<slug>/memory` → symlink →
`repo/.claude/memory` (32 versioned files, single source of truth in the project).
**link-memory.sh bug fixed**: it shipped at two depths (`.claude/bin` in claude-hub,
`.claude/hub/bin` in subtree consumer) so the hardcoded `../..` was wrong in the subtree
(computed `<root>/.claude` → bogus `.claude/.claude/memory` + wrong slug). Now derives
root via `git rev-parse --show-toplevel` (correct at any depth). Shipped to main in BOTH
repos + resynced via subtree split/push/pull. See [[claude-auto-memory-mechanics]].

**THE GOAL (settled):** every checkout is an external worktree under
`<repo>.d/<namespace>/<branch>` (NOT nested in `.claude/worktrees/`), so VSCode
roots a window at the worktree → indexed code + chat in ONE window. `main` becomes
just `<repo>.d/main`. Eventually: Claude's whole config (settings, CLAUDE.md,
rules, agents, memory) version-controlled in the **hub repo** via
`CLAUDE_CONFIG_DIR`, retiring nix-darwin's `~/.claude` provisioning. Bedrock auth
(SSO `hyland`) keeps secrets external → safe to commit config.

**DONE — rke2lab migrated + reconciled (this session):**
- Layout now `rke2lab.d/{main, chore/external-worktree-operating-model,
  feature/unitrepo-resolution-core}` via `git clone` (old `/rke2lab` gone). Done by
  the USER in terminal.
- `git worktree repair` ran → all 3 registrations point at real `rke2lab.d` paths,
  none prunable. (Earlier false "repair MISSING" was a man-page-less false negative;
  it IS available, git 2.51.2.)
- Memory slug repointed: `~/.claude/projects/-…-rke2lab-d-main/memory` →
  `rke2lab.d/main/.claude/memory` (was dangling at deleted `/rke2lab`).
- **`link-memory.sh` dot/dash bug fixed** (commit b49895fd on branch
  `chore/external-worktree-operating-model`) — see [[claude-auto-memory-mechanics]] §3.
- **fleet symlinks**: rule = `$(dirname <worktree>)/fleet -> /…/nxmatic/fleet`
  (absolute, depth-independent). Created at `rke2lab.d/fleet` (for main, 1-level)
  and `rke2lab.d/{chore,feature}/fleet` (2-level). All `../fleet/flox/*` resolve. The
  namespace-as-subdir means fleet symlink is PER-DIRECTORY-LEVEL, not one-per-repo.
- Pulumi dev state + origin remote came across the clone intact. unitrepo parked
  work safe (tip 583edf62 + uncommitted manifest.lock).

**DONE — hub repo renamed claude-memory → claude-hub (this session, by USER + me):**
- GitHub repo renamed (`gh`), filesystem dirs renamed, clone moved to
  `claude-hub.d/main`, worktrees repaired, remote URL now `claude-hub.git`. (USER did
  the git/fs moves in terminal.)
- Slug bridge repointed by me: new `~/.claude/projects/-…-claude-hub-d-main/memory`
  → `claude-hub.d/main/.claude/memory`; old dangling `…-claude-memory` slug symlink
  removed (transcripts left).
- Stale `claude-memory` refs updated in: hub README + MEMORY.md headers, rke2lab
  MEMORY.md hub path, this note, workspace-driven-by-need, nix-darwin-home.code-workspace
  (rke2lab.code-workspace was already correct). Tier-3 historical docs
  (MEMORY-STRUCTURE-PLAN/SPEC) deliberately LEFT mentioning claude-memory — they
  document the repo's original creation under that name (rewriting = falsifying history).

**DESIGN PIVOTS (what died, with proof):**
- **Per-branch / per-worktree memory isolation = IMPOSSIBLE** — auto-memory is
  repo-wide ([[claude-auto-memory-mechanics]] §1). The original spec's §5 is void.
- **Hub-as-subtree (.claude/hub) = DROPPED.** Its only benefit was per-branch memory
  isolation; with that impossible, subtree adds machinery for zero gain. (Subtree for
  pure history-backing still *works*, but isn't worth it alone.)
- **bare-repo `rke2lab.d/main` = BACKLOG, gated on nikopol** — bioskop exports the
  /var/lib/git volume; nikopol mounts it via /net and can't yet handle bare-repo +
  worktree path refs. (We did a CLONE, not bare, so this is moot for now but matters
  for the eventual bare end-state.)

**★ PROBE RESULT (2026-06-15) — CLAUDE_CONFIG_DIR WORKS ON macOS, relocates the FULL
tree.** Ran `CLAUDE_CONFIG_DIR=claude-hub.d/spike/config-dir-probe claude`. `git
status` in the worktree showed Claude created, at the config-dir ROOT (NOT under
`.claude/`): `settings.json`, `.claude.json`, `plugins/`, `sessions/`,
`history.jsonl`, `cache/`, `backups/`. So the whole `~/.claude` footprint follows the
var on darwin — the expert's "undocumented/untested for macOS" is RESOLVED: it works.
Settled facts:
- **`.claude.json` DOES follow** into the config dir (it's NOT left at $HOME). At fresh
  state it held only app-state (startup counts, onboarding flags, userID) — NO creds
  (creds = Keychain/SSO). Still machine-local churn → gitignore it.
- **Config-dir layout = FLAT root** (`settings.json` at root, not `.claude/settings.json`).
  This differs from the current hub layout (`.claude/memory/`) → the config-home
  refactor must reconcile the two shapes.
- **Bedrock switch is CONFIG not secret**: the `env` block
  (`CLAUDE_CODE_USE_BEDROCK=1`, `AWS_PROFILE=ai-tools-shared`, region) lives in
  `settings.json`. Safe to commit. Real auth = AWS SSO session `hyland` via AWS_PROFILE,
  external to the config dir. So a relocated config that's missing the env block →
  Claude won't use Bedrock (falls to OAuth); the fix is ensure settings.json has the
  env block, NOT touch creds.

**`.gitignore` DESIGN (from the probe footprint):**
- COMMIT (durable): `settings.json`, `.claude/` (memory, CLAUDE.md, rules, agents).
- IGNORE (ephemeral/machine-local/secret): `.claude.json`, `sessions/`, `history.jsonl`,
  `cache/`, `backups/`, `plugins/`.

**NEXT STEPS (ordered — prerequisite chain):**
1. ✅ DONE — probe (above). TEAR DOWN the spike worktree when finished
   (`git worktree remove claude-hub.d/spike/config-dir-probe` + delete branch).
2. **Give the hub a `link-memory.sh`** (it has none; copy the FIXED rke2lab one with
   the dot/dash slug fix) — so the hub's own slug bridge is reproducible after moves.
3. **Refactor hub into config-home** (probe SUCCEEDED → viable): restructure to the
   FLAT config-dir shape (settings.json at root), add the `.gitignore` above, set
   CLAUDE_CONFIG_DIR (in the flox ai-agents env or shell profile) to the hub main,
   commit config (not secrets). Retires nix-darwin's ~/.claude provisioning.
4. rke2lab spec/plan at `wip/superpowers/{specs,plans}/2026-06-15-external-worktree-*`
   on branch `chore/external-worktree-operating-model` STILL DESCRIBE THE ABANDONED
   design (subtree, autoMemoryDirectory isolation) — rewrite to the real model, or
   replace with a findings doc. NOT yet done.

**★ CONFIG-HOME MATERIALIZATION RUNBOOK (the next chantier — execute in a FRESH
session rooted at the hub).** Probe already PROVED CLAUDE_CONFIG_DIR works on macOS
+ relocates the full tree (see PROBE RESULT above). Goal: make `claude-hub.d/main`
the version-controlled CLAUDE_CONFIG_DIR, retiring nix-darwin's ~/.claude provisioning.

`~/.claude` inventory captured 2026-06-15 (classify on copy):
- **DURABLE → commit to hub:** `settings.json` (incl. the Bedrock env block — config,
  not secret), `CLAUDE.md` (global user instructions), `skills/` (personal skills).
  Memory ALREADY in hub at `.claude/memory/`.
- **EPHEMERAL / machine-local → .gitignore:** `.claude.json`, `projects/` (transcripts
  + per-cwd slug symlinks), `sessions/`, `session-env/`, `shell-snapshots/`,
  `history.jsonl`, `file-history/`, `cache/`, `paste-cache/`, `backups/`, `ide/`,
  `mcp-needs-auth-cache.json`, `.last-cleanup`, `plans/`.
- **DECIDE in the fresh session (genuinely uncertain, do NOT pre-decide):**
  (a) `plugins/` — auto-installed from the official marketplace (the
  `officialMarketplaceAutoInstalled` flag in .claude.json); likely gitignore + let it
  re-install, but confirm superpowers/atlassian/claude-md-management reappear.
  (b) memory placement: probe layout is FLAT-ROOT (`settings.json` at config-dir root)
  but Claude's auto-memory wants `projects/<slug>/memory` — reconcile how the hub's
  `.claude/memory/` content is reached when the hub IS the config dir (slug symlink? a
  different layout?). This is the one non-obvious mechanism left.
- **Steps:** (1) build the hub config tree (copy durable parts to flat-root layout);
  (2) write `.gitignore` per the lists above; (3) set CLAUDE_CONFIG_DIR=hub-main in the
  flox `ai-agents` env (`/var/lib/git/nxmatic/fleet/flox/ai-agents`) so every launch
  uses it; (4) VERIFY a fresh session loads settings+memory+plugins+Bedrock from the
  hub; (5) ONLY THEN `mv ~/.claude ~/.claude~bak` as safety net (NEVER first — a
  premature mv leaves no active config). Commit config (not secrets); push to claude-hub.git.

**Open uncommitted state to not lose:** rke2lab branch
`chore/external-worktree-operating-model` has the spec/plan + link-memory fix
(committed there, NOT on main, NOT integrated). The hub spike worktree is throwaway.

See [[claude-auto-memory-mechanics]] (the harness facts), [[worktree-per-conversation]]
(isolation rule, refined to per-branch), [[branch-namespaces]] (archived/ added this
session too), [[rke2lab:pulumi-stack-per-worktree-backlog]], [[rke2lab:sops-worktree-smudge-noise]].
