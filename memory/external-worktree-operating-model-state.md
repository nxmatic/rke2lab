---
name: external-worktree-operating-model-state
description: "★ CHANTIER: worktree-rooted Claude config. External-worktree layout DONE (rke2lab.d, claude-hub.d). NEW MODEL (replaces old global-CONFIG_DIR runbook): each repo's .claude/ is the SPECIFIC layer; the COMMON layer is a squashed claude-hub subtree at .claude/hub; CLAUDE_CONFIG_DIR=<worktree>/.claude/hub makes hub=USER scope, worktree=PROJECT scope (native cascade, no build). Spec+plan written; step-1 PROVEN in a test workspace. Open: extension needs ABSOLUTE CONFIG_DIR (no ${workspaceFolder} substitution); integration to main pending."
metadata:
  node_type: memory
  type: project
  originSessionId: 8e678ad3-a55c-4557-ad9c-921e83b18f14
---

**THE MODEL (settled + proven 2026-06-16).** Every checkout is an external worktree
under `<repo>.d/<namespace>/<branch>` so one VSCode window = indexed code + chat.
Claude's config is *worktree-rooted*, in three layers (read specific→general):

1. **SPECIFIC** — the repo's own `.claude/` (project `CLAUDE.md`, project `settings.json`, project `memory/`). Read first.
2. **GENERAL** — a squashed git subtree of `claude-hub`'s `.claude/` at `<worktree>/.claude/hub` (shared `instructions.md`, USER-scope `settings.json` with `enabledPlugins`+marketplaces, `memory/`, `skills/`). Reached last.
3. **EPHEMERAL** — gitignored, per-worktree (lands under `.claude/hub/` when that is the CONFIG_DIR).

**★ STARTUP RECIPE — how to CREATE a worktree under this model (the gesture, not just the
fact; learned 2026-06-16 when a cold session used the `EnterWorktree` harness tool and landed
under `.claude/worktrees/` — the WRONG place this model rejects).** `EnterWorktree` hard-codes
`.claude/worktrees/<branch>` and is FORBIDDEN here; use plain `git worktree add` at the external
path. **The `worktree` skill is now the canonical create/teardown recipe — follow it; this note
keeps only the state/history.** Run from any existing worktree of the repo (or `<repo>.d/main`):

1. **Source branch = the branch you're currently on, by default** (new work stacks on top):
   `src="$(git rev-parse --abbrev-ref HEAD)"`. Use `origin/<default-branch>` as base ONLY when you
   explicitly want a fresh trunk base — then `git fetch origin <default-branch>` first. (Corrected
   2026-08-14 — earlier this recipe hardcoded `origin/<default-branch>`.)
2. `git worktree add -b <namespace>/<slug> <repo>.d/<namespace>/<slug> "$src"`
   (namespace by kind: `feature/`/`chore/`/`design/`/`refactor/`/`spike/`).
3. **Re-smudge sops** (the checkout precedes `.sops.yaml` visibility → secrets land ENCRYPTED):
   for each still-encrypted sops file `rm <file> && git checkout -- <file>`; verify no real secret
   keeps `ENC[` — beware false positives (docs/code/schemas can mention `ENC[` as literal text).
   Full diagnosis in [[sops-worktree-smudge-noise]].
4. `cd` into the new worktree. The cleanup/finish recipe (the mirror gesture) is below.

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

**★ SUPERSEDED 2026-08-14 — the memory bridge + `link-memory.sh` are REMOVED.** Everything
about the *memory* symlink above is history: `autoMemoryDirectory` (absolute path in the
per-worktree `.claude/settings.local.json`) now pins auto-memory read+write to the tracked
`<wt>/.claude/memory` directly — slug- and reader-independent (VERIFIED on darwin). `link-memory.sh`
is DELETED. Only the SESSIONS transcript bridge survives, now as `link-sessions.sh`. Likewise the
`claudeProcessWrapper`/`claude-config-home-wrapper.sh` and `CONFIG_DIR=<wt>/.claude/hub` described
above were replaced by the clean-split `.code-workspace` env var (`CLAUDE_CONFIG_DIR=<wt>/.claude`,
NOT `.claude/hub`). Current reference: [[claude-memory-cascade-state]].

Wiring: `CLAUDE_CONFIG_DIR=<worktree>/.claude/hub` → hub=USER scope, worktree=PROJECT
scope; Claude **deep-merges natively** (no build step — there is NO `include` in
settings.json, but the scope cascade does it). Instructions use the project
`CLAUDE.md` ending with `@.claude/hub/instructions.md` (tail import = general last;
auto-loading the hub as USER scope would load general FIRST, wrong order).
nix-darwin **keeps** providing Bedrock via `home.sessionVariables` (shell vars,
location-independent); its settings.json-seeding becomes redundant (retire later).

**ARTIFACTS (on branch `chore/worktree-config-home` in BOTH repos, NOT integrated):**
- Hub spec: `wip/specs/2026-06-15-worktree-config-home-design.adoc` (4 C4 figures).
- Hub plan: `wip/plans/2026-06-15-worktree-config-home-step1.md`.
- Hub `.claude/` now carries the shareable tree (instructions.md, settings.json with 3 plugins, skills/, bin/link-memory.sh CONFIG_DIR-aware, .gitignore); local split branch `split/claude-hub/dot-claude`.
- rke2lab `chore/worktree-config-home` worktree has the subtree at `.claude/hub` (added from the LOCAL hub via remote `claude-hub-local`, squashed) + `@import` at CLAUDE.md tail + README-SUBTREE.md.
- Disposable test workspace: `/private/var/lib/git/nxmatic/rke2lab-config-home-test.code-workspace`.

**STEP-1 PROOF (2026-06-16, via the test workspace, fresh extension-launched session):**
- ✅ Config home resolves to the worktree `.claude/hub`; tracked files + ephemeral coexist there.
- ✅ Hub settings load; all 3 plugins (superpowers, claude-md-management, atlassian) declared enabled via the cascade.
- ✅ Bedrock works (`1 / ai-tools-shared`) DESPITE config-dir — proves nix shell-env is location-independent.
- ✅ `@import` of hub instructions loads (progress-narration rule present) — and it loaded even when CONFIG_DIR was broken, because the import resolves from cwd's CLAUDE.md, NOT via CONFIG_DIR → instruction layer is robust.

**★ KEY GOTCHA + ITS FIX (PROVEN 2026-06-16): the VSCode Claude extension does NOT
substitute `${workspaceFolder}`** in `claudeCode.environmentVariables` — it exports
the LITERAL string (round-1 test failed exactly here). **SOLVED via
`claudeCode.claudeProcessWrapper`** = `<some-worktree>/.claude/hub/bin/claude-config-home-wrapper.sh`
(committed in the hub subtree, so present in every worktree). The extension launches
the wrapper with the real claude binary as `"$@"`; the wrapper computes
`CLAUDE_CONFIG_DIR` from `git rev-parse --show-toplevel` of the cwd and `exec "$@"`.
ONE fixed wrapper path serves EVERY worktree (dynamic resolution), and the sidebar
UX is untouched (unlike `useTerminal:true`). PROVEN: extension-launched session in
the test workspace shows the correct per-worktree config home.
- Wrapper-contract facts (from diagnostic logging): on darwin-arm64 `"$@"` is the
  native binary `resources/native-binary/claude` (no node/cli.js). The extension
  invokes the wrapper TWICE per startup: the main session with cwd=the worktree
  (→ var set), and `auth status --json` with cwd=`/` (→ no git root → fall through
  WITHOUT setting it; harmless since auth is Bedrock/AWS-SSO, external to the dir).
  The `[[ -n "$root" && -d "$root/.claude/hub" ]]` guard is REQUIRED for that.
- Extension options audited vs user settings: only `usePythonEnvironment:false` is a
  real win (user is Java/Nix, not Python); `respectGitIgnore:true` is load-bearing
  (Claude ignores the gitignored ephemeral in `.claude/hub`). `useCtrlEnterToSend`,
  `disableLoginPrompt` rejected by user as no-value. `claudeCode.*` settings are
  scope=window (per-workspace), NOT provisioned by nix (nix sets shell env only).

**★ SHIPPED to main (2026-06-16).** Both repos integrated + pushed:
- claude-hub `origin/main` = `a42a8db` (merge): carries the shareable `.claude/` tree
  (instructions, settings w/ coarse-grain permissions, wrapper, skills) + spec + plan;
  split branch `split/claude-hub/dot-claude` pushed to origin.
- rke2lab `origin/main` = `0b5262a2` (merge): `.claude/hub` subtree (refreshed from
  GitHub, NOT the local path), `@.claude/hub/instructions.md` at CLAUDE.md tail.
- Real `rke2lab.code-workspace` wired: `claudeCode.claudeProcessWrapper` = absolute
  path to `main`'s wrapper. `.code-workspace` files are NOT git-tracked (verified) →
  the one hard-coded absolute path lives outside history; the wrapper resolves
  CONFIG_DIR dynamically per-worktree, so nothing absolute is committed.
- Coarse-grain permissions LIVE: hub settings = `acceptEdits` + allow
  Read/Grep/Glob/Bash/Edit/Write + 18-rule system denylist (pulumi up/destroy,
  kubectl/helm mutating, incus, nix switch/rebuild, rm -rf, mv ~/.claude) + sudo=ask;
  denylist double-anchored in `~/.claude` too (159 old per-command rules purged,
  backup at `~/.claude/settings.json~bak-perms`).

**OPEN / NEXT (none blocking; pick up anytime):**
- C5 (memory slug = option 1: slug→PROJECT memory, hub via `[[hub:..]]`) never formally
  re-run — verify in a fresh main-rooted session if memory load looks off.
- Retire nix-darwin settings-seeding activations once committed hub settings prove they replace them.
- Generalize to other repos (maven fleet, docrepo); write canonical `hub/docs/operating-model.adoc`.
- The new permissions policy hasn't run a full real session yet — keep `~bak-perms` until validated in use.

**★ CLEANUP / FINISHING PROVENANCE (cross-repo rule, learned 2026-06-16 rke2lab walker-retirement).**
Because every worktree lives at `<repo>.d/<namespace>/<branch>` (sibling of `main`), NOT under
`.claude/worktrees/`, the `superpowers:finishing-a-development-branch` skill's provenance check is
WRONG for this operating model: that skill only treats worktrees under `.claude/worktrees/` (or
`worktrees/`, `~/.config/superpowers/worktrees/`) as "ours to clean up" and leaves everything else
as "harness-owned, do not remove". Under THIS model a `<repo>.d/<branch>` worktree IS user-managed
and removing it once merged is the normal, expected finish — do not refuse on provenance grounds.
Recipe (run from `<repo>.d/main`, never cwd-inside the target): confirm not-inside + branch merged
(`git branch --merged HEAD`) + tree clean (ignore the expected `.flox/env/manifest.lock` + sops
re-smudge noise, `--force` is fine for that) → `git worktree remove [--force] <repo>.d/<branch>` →
`git worktree prune` → `git branch -d <branch>` (use `-d`, refuses if unmerged). NOTE: a
`<repo>.d/<namespace>/` parent dir (e.g. `rke2lab.d/feature/`) legitimately retains a `.flox.d`
symlink (→ fleet flox) after removal — that is the include-resolution scaffolding for the next
worktree placed there, NOT residue; leave it. See [[rke2lab:sops-worktree-smudge-noise]].

**★ DEV-FLOW DISCIPLINE — hub-subtree sync at BOTH ends of a session (the gap we hit 2026-06-16).**
The hub subtree is BIDIRECTIONAL (see README-SUBTREE.md): a consumer like rke2lab can originate hub
edits and push them up. The discipline that was MISSED must become systematic — belongs in the
canonical `hub/docs/operating-model.adoc` (the open "generalize" item above):

- **AT SESSION START** (opening a worktree): sync-down the hub subtree first
  (`subtree pull --prefix=.claude/hub claude-hub split/claude-hub/dot-claude --squash`) so you build
  on the current hub, not a stale squash. Also verify the standalone `claude-hub.d/main` has no
  unpushed commits — if it does, that is an ERROR to fix (verify + push) before starting, else the
  split you pull from is behind.
- **DURING**: normally make hub edits in the CONSUMER subtree (`<repo>.d/main/.claude/hub/…`) and
  publish them up via split. You MAY instead edit `claude-hub.d/main` directly — but then those
  edits MUST be made available by an immediate `git push origin main` (the mirror of our case: a
  direct hub edit left unpushed makes every consumer pull a split branch that is behind the real
  hub). "Edited the hub directly" and "pushed the hub" are one atomic step.
- **AT SESSION END, BEFORE THE MERGE**: sync-up — `subtree split --prefix=.claude/hub
  --branch=split/<repo>/dot-claude` (**NO `--rejoin`**) → `git push claude-hub split/<repo>/dot-claude`
  → in the hub `subtree pull --prefix=.claude origin split/<repo>/dot-claude --squash`, then push the
  hub. Keep `--squash` consistent both directions; **never `--rejoin`** — after a squash pull it fails
  with "refusing to merge unrelated histories" (corrected 2026-08-14; `README-SUBTREE.md` and the
  `worktree` skill are authoritative).
- WHAT WENT WRONG this time: `claude-hub.d/main` carried 6 unpushed doc-only commits AND rke2lab's
  subtree was behind the hub origin, so a naive sync-up would have built on a stale base. Proven
  correct order: push the hub's pending commits → re-split + push the split branch → sync-down into
  rke2lab (resolve the squash conflict, theirs = hub canonical) → THEN edit + sync-up.

**★ SUPERSEDED 2026-08-14 by `autoMemoryDirectory`.** Memory is now per-worktree in the tracked
`<worktree>/.claude/memory` (see the SUPERSEDED banner above), NOT in `main` via a home symlink — so
the specific home-symlink→main desync below no longer occurs. What SURVIVES is the general hygiene:
commit memory edits before finishing so they ride into `main` at MERGE — but you commit them in the
worktree now, not directly in `<repo>.d/main`. Kept below for history:

**★ FAILURE MODE — uncommitted memory in `main` desyncs the next session (named 2026-06-17).**
Auto-memory writes land in `<repo>.d/main/.claude/memory` (the home symlink
`~/.claude/projects/<slug>/memory` → main's `.claude/memory`). A session writes memory there but
does NOT auto-commit; the NEXT session opens a fresh worktree off `origin/main`, which lacks those
writes → it reads STALE committed memory while the live truth sits UNCOMMITTED in main's tree. Hit
2026-06-17: the Step-2 "design SHIPPED, 3 tasks done" rewrite sat uncommitted in main for a whole
session; my startup prompt (written before it) sent me to redo already-done work. The user's framing:
"main always has the latest, but it never propagates." THE DISCIPLINE (make systematic):
- **AT SESSION START**, before branching: in `<repo>.d/main` run `git status -s .claude/memory/` — if
  dirty, that is the PREVIOUS session's unflushed truth. Read it, reconcile it, COMMIT + PUSH it to
  `origin/main` BEFORE `git worktree add` (else the fresh worktree starts stale). This is the memory
  analogue of the hub-subtree "verify claude-hub.d/main has no unpushed commits" start-gate above.
- **AT SESSION END**: commit memory edits in main and `git push origin main` — "wrote memory" and
  "pushed memory" are one atomic step, same rule as the direct-hub-edit rule below. A memory file left
  uncommitted is indistinguishable to the next session from work never done.
- Memory lives in main (single source of truth, home symlink), so committing it in `main` — not in the
  feature worktree — is correct; do it from `<repo>.d/main`.
**DESIGN PIVOTS (dead, do not revive):** old "single global CLAUDE_CONFIG_DIR=hub"
runbook → SUPERSEDED by this worktree-rooted model. Per-branch/per-worktree memory
isolation = IMPOSSIBLE (auto-memory is repo-wide, [[claude-auto-memory-mechanics]]).
Hub-as-subtree-for-memory-isolation = DROPPED; but hub-subtree-for-SHARED-CONFIG
(this chantier) is the live, proven use.

See [[claude-auto-memory-mechanics]] (harness facts), [[diagram-preview-file]]
(kroki + mermaid dialect, used for the C4 figures), [[standing-approval-subagent-execution]],
[[rke2lab:sops-worktree-smudge-noise]] (hit during Phase B worktree add).
