# Claude Memory Three-Tier Cascade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire Claude's file-memory into a three-tier cascade (worktree → hub → home) by canonicalizing the `link-memory.sh` helper, wiring the per-worktree memory symlink, and adding a minimal nix home seed — without ever touching the filesystem below a slug directory.

**Architecture:** The content migration is already DONE (hub = 34 cross-cutting notes via subtree; worktree = 37 project-specific notes; zero doublons). This plan is WIRING + HYGIENE only. Three independent deliverables: (1) one canonical `link-memory.sh` in the hub `bin/` with the two stale copies removed; (2) a verified per-worktree slug→memory symlink (the family currently has one MISSING worktree as a live test case); (3) a nix `claudeCodeSeedMemory` activation mirroring the proven `claudeCodeSeed` seed-if-absent pattern. A fourth task fixes the stale slug-encoding documentation that would otherwise re-teach the bug.

**Tech Stack:** bash (the helper), git (subtree + worktrees + tracking), filesystem symlinks, nix-darwin / home-manager (the home seed), Claude Code file-memory (`MEMORY.md` index + topic files).

## Global Constraints

- **FS invariant (ABSOLUTE):** manipulate the filesystem ONLY at the slug level — create/repoint `<worktree>/.claude/hub/projects/<slug>/memory`. NEVER touch anything inside a slug directory (`*.jsonl` transcripts, `tool-results/`, per-session subdirs). Claude's internal slug layout is volatile; treat it as opaque.
- **Slug encoding:** the slug is the working-tree root path (`git rev-parse --show-toplevel`) with BOTH `/` AND `.` replaced by `-` → `sed 's:[/.]:-:g'`. Never `s:/:-:g` (the original bug — `rke2lab.d` must become `rke2lab-d`).
- **Config-home anchor:** the symlink lives under `${CLAUDE_CONFIG_DIR:-$HOME/.claude}` (= `<worktree>/.claude/hub` under the wrapper), NEVER hardcoded `$HOME/.claude`.
- **No backwards-compat / no dead code:** single-developer repo. When a path is superseded, delete it entirely in the same change (per `sequential-no-compat-workflow`). The 2 stale helper copies are deleted, not deprecated.
- **Uniformity:** one canonical helper; all references point at it; no "legacy" variants left behind.
- **Plan/spec location:** working assets live in `wip/`, never `docs/` (per `superpowers-assets-in-wip`).
- **Shared artifacts in English:** scripts, comments, commit messages en-US (per `shared-artifacts-in-english`).
- **rke2lab is solo:** no GitHub PR; integrate by direct rebase + ff-merge to origin/main, then remove the worktree (per `rke2lab-solo-no-pr-merge-direct`). The nix change lands in `nix-darwin-home` (a DIFFERENT repo), on its own branch.
- **Commit each task** when its deliverable is green. Frequent commits.

---

## File Structure

Files this plan creates or modifies, with one responsibility each:

- `.claude/hub/bin/link-memory.sh` — **canonical** helper (already fixed on both axes, committed b44a3656). Keep; becomes the single source of truth. Travels via the hub subtree.
- `.claude/bin/link-memory.sh` — stale duplicate (old `s:/:-:g` + `$HOME/.claude`). **Delete.**
- `.claude/hub/skills/track-claude-memory-in-repo/link-memory.sh` — stale duplicate next to the skill. **Delete** (the skill references the hub `bin/` copy instead).
- `.claude/hub/skills/track-claude-memory-in-repo/SKILL.md` — fix step 1 slug derivation (`s:/:-:g` → `s:[/.]:-:g`) and step 7 (point at the hub `bin/` canonical helper instead of "copy the file next to this SKILL.md").
- `<worktree>/.claude/hub/projects/<slug>/memory` — the per-worktree symlink (runtime, gitignored). Created/verified by the helper. `target-module-layout` is currently MISSING.
- `nix-darwin-home/modules/home-manager/claude-code.nix` — add `home.activation.claudeCodeSeedMemory` + a `seedMemory` option carrying the minimal home seed (profile only).
- `nix-darwin-home/modules/home-manager/claude-code.d/memory-seed/` (or inline store text) — the seed content: `MEMORY.md` + `user-profile-senior-dev.md`.

---

### Task 1: Canonicalize `link-memory.sh` in the hub + remove the two stale copies

**Files:**
- Keep: `.claude/hub/bin/link-memory.sh` (canonical — already correct, committed b44a3656)
- Delete: `.claude/bin/link-memory.sh`
- Delete: `.claude/hub/skills/track-claude-memory-in-repo/link-memory.sh`
- Verify: `.claude/hub/.gitignore` does not exclude `bin/` or `bin/*.sh`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: the canonical helper at `.claude/hub/bin/link-memory.sh`. Its contract — run with cwd anywhere inside a worktree (or with `CLAUDE_CONFIG_DIR` exported by the wrapper); it resolves `repo_root` via `git -C "$script_dir" rev-parse --show-toplevel`, computes `slug="$(printf '%s' "$repo_root" | sed 's:[/.]:-:g')"`, and creates `"${CLAUDE_CONFIG_DIR:-$HOME/.claude}/projects/$slug/memory" -> "$repo_root/.claude/memory"`. Idempotent; refuses to clobber a real (non-symlink) dir. Task 2 invokes this helper.

- [ ] **Step 1: Confirm the canonical copy is already correct (no edit needed)**

Read the canonical helper and verify both axes are present:

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/chore/claude-memory-cascade
grep -n "sed 's:\[/\.\]:-:g'" .claude/hub/bin/link-memory.sh
grep -n 'CLAUDE_CONFIG_DIR:-\$HOME/.claude' .claude/hub/bin/link-memory.sh
grep -n 'rev-parse --show-toplevel' .claude/hub/bin/link-memory.sh
```

Expected: all three grep lines match (slug encodes `/` and `.`; anchored under `$CLAUDE_CONFIG_DIR`; repo-root via git toplevel). If any is missing, STOP — the canonical copy regressed and must be fixed before dedup.

- [ ] **Step 2: Confirm the two stale copies differ and are git-tracked**

```bash
diff .claude/bin/link-memory.sh .claude/hub/bin/link-memory.sh >/dev/null && echo "IDENTICAL (unexpected)" || echo "DIFFER (stale, expected)"
diff .claude/hub/skills/track-claude-memory-in-repo/link-memory.sh .claude/hub/bin/link-memory.sh >/dev/null && echo "IDENTICAL (unexpected)" || echo "DIFFER (stale, expected)"
git ls-files .claude/bin/link-memory.sh .claude/hub/skills/track-claude-memory-in-repo/link-memory.sh
```

Expected: both report `DIFFER (stale, expected)`; both paths print (tracked).

- [ ] **Step 3: Verify the hub `.gitignore` does not exclude the canonical helper**

```bash
git -C /private/var/lib/git/nxmatic/rke2lab.d/chore/claude-memory-cascade \
  check-ignore -v .claude/hub/bin/link-memory.sh; echo "exit=$?"
```

Expected: `exit=1` and no output (not ignored — good). If it prints a matching rule, add `!bin/` and `!bin/*.sh` negations to `.claude/hub/.gitignore` before proceeding.

- [ ] **Step 4: Remove the two stale copies via git**

```bash
git rm .claude/bin/link-memory.sh
git rm .claude/hub/skills/track-claude-memory-in-repo/link-memory.sh
```

Expected: both staged for deletion. Then confirm exactly one copy remains:

```bash
find . -name link-memory.sh -not -path './.git/*'
```

Expected: a single line — `./.claude/hub/bin/link-memory.sh`.

- [ ] **Step 5: Verify the canonical helper still runs idempotently (no FS damage)**

The helper is idempotent and our own worktree's symlink already exists and is correct, so a run must report "ok" and change nothing:

```bash
bash .claude/hub/bin/link-memory.sh
ls -ld "$CLAUDE_CONFIG_DIR/projects/-private-var-lib-git-nxmatic-rke2lab-d-chore-claude-memory-cascade/memory"
```

Expected: prints `ok: symlink already points at …/chore/claude-memory-cascade/.claude/memory`; the `ls` shows the symlink unchanged.

- [ ] **Step 6: Commit**

```bash
git add -A .claude/
git commit -m "chore(memory): canonicalize link-memory.sh in hub, drop 2 stale copies

The hub copy (b44a3656) is the only correct one — slug encodes / and .,
anchored under \$CLAUDE_CONFIG_DIR, repo-root via git toplevel. Remove the
stale .claude/bin and skill-dir duplicates (old s:/:-:g + \$HOME/.claude).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Wire the per-worktree memory symlink (repair the MISSING worktree as a live test)

**Files:**
- Invoke: `.claude/hub/bin/link-memory.sh` (from Task 1)
- Create: `<worktree>/.claude/hub/projects/<slug>/memory` symlink for any worktree missing it (currently `design/target-module-layout`)

**Interfaces:**
- Consumes: the canonical helper from Task 1 (its run-anywhere contract).
- Produces: a verified invariant — every worktree in the family has `…/projects/<correctly-encoded-slug>/memory -> <that-worktree>/.claude/memory`, and no cross-worktree leakage. Nothing downstream depends on this; it is a terminal verification task.

- [ ] **Step 1: Write the failing check — survey slug-symlink health across the family**

This is the "test": a script that asserts every worktree has its slug→memory symlink. Run it FIRST to capture the failing state.

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/chore/claude-memory-cascade
for wt in $(git worktree list --porcelain | awk '/^worktree /{print $2}'); do
  slug="$(printf '%s' "$wt" | sed 's:[/.]:-:g')"
  link="$wt/.claude/hub/projects/$slug/memory"
  printf '%-40s ' "$(basename "$wt")"
  if [[ -L "$link" ]]; then printf 'LINK -> %s\n' "$(readlink "$link")"
  elif [[ -e "$link" ]]; then echo "REAL DIR (FORBIDDEN — investigate)"
  else echo "MISSING"; fi
done
```

Expected (failing): `main` LINK, `claude-memory-cascade` LINK, `target-module-layout` **MISSING**.

- [ ] **Step 2: Repair the MISSING worktree by running the canonical helper from inside it**

The helper resolves repo-root from cwd, so invoke it with cwd = the target worktree. This touches ONLY the slug-level `memory` symlink (FS invariant honored).

```bash
WT=/private/var/lib/git/nxmatic/rke2lab.d/design/target-module-layout
HELPER=/private/var/lib/git/nxmatic/rke2lab.d/chore/claude-memory-cascade/.claude/hub/bin/link-memory.sh
( cd "$WT" && CLAUDE_CONFIG_DIR="$WT/.claude/hub" bash "$HELPER" )
```

Expected: `linked: …/target-module-layout/.claude/hub/projects/-…-design-target-module-layout/memory -> …/target-module-layout/.claude/memory`.

Note: if that worktree has no `.claude/memory/` dir, the helper exits 1 ("nothing to link to") — that means the worktree has no tracked memory yet, which is a content concern OUT OF SCOPE here; record it and move on. Do NOT create memory content to satisfy the link.

- [ ] **Step 3: Re-run the survey to verify it now passes**

Re-run the Step 1 loop. Expected: all worktrees report `LINK -> <their own>/.claude/memory`; none MISSING, none `REAL DIR`.

- [ ] **Step 4: Verify no cross-worktree leakage (each slug points at its OWN worktree)**

```bash
for wt in $(git worktree list --porcelain | awk '/^worktree /{print $2}'); do
  slug="$(printf '%s' "$wt" | sed 's:[/.]:-:g')"
  tgt="$(readlink "$wt/.claude/hub/projects/$slug/memory" 2>/dev/null)"
  case "$tgt" in
    "$wt/.claude/memory") echo "OK   $(basename "$wt")";;
    "") echo "SKIP $(basename "$wt") (no link)";;
    *) echo "LEAK $(basename "$wt") -> $tgt";;
  esac
done
```

Expected: only `OK` / `SKIP` lines, never `LEAK`.

- [ ] **Step 5: No commit (runtime symlinks are gitignored)**

The slug symlinks live under `projects/` which the hub `.gitignore` excludes — there is nothing to commit. Confirm the working tree is clean of this work:

```bash
git status --porcelain
```

Expected: empty (the symlink is gitignored; no tracked file changed in this task).

---

### Task 3: Add the nix `claudeCodeSeedMemory` activation (minimal home seed = profile only)

**Files:**
- Modify: `nix-darwin-home/modules/home-manager/claude-code.nix` (add `seedMemory` option + `home.activation.claudeCodeSeedMemory`)
- Create: `nix-darwin-home/modules/home-manager/claude-code.d/memory-seed/MEMORY.md`
- Create: `nix-darwin-home/modules/home-manager/claude-code.d/memory-seed/user-profile-senior-dev.md`

**Interfaces:**
- Consumes: the proven `claudeCodeSeed` pattern in the same file (lines ~117-126: `lib.hm.dag.entryAfter [ "writeBoundary" ]`, `if [ ! -e ... ]` guard, `$DRY_RUN_CMD install`).
- Produces: on `darwin-rebuild`, if `~/.claude/memory/` is absent, it is created and seeded from the nix store with the minimal profile; if present, left untouched (Claude owns it). Read at runtime via `[[home:name]]` links only — the home tier is NOT the config-home.

> **NOTE — separate repo + branch.** This task lands in `nix-darwin-home`, not rke2lab. Create the branch there first (`git -C /private/var/lib/git/nxmatic/nix-darwin-home fetch origin develop && git -C … worktree add …` per the external-worktree model, OR a plain branch if the user is working in-place). Confirm the working location with the user before editing — do not assume the rke2lab worktree layout applies to nix-darwin-home.

- [ ] **Step 1: Create the seed `MEMORY.md` (minimal home index)**

`nix-darwin-home/modules/home-manager/claude-code.d/memory-seed/MEMORY.md`:

```markdown
# Memory index — home (machine-wide, minimal)

The home tier seeds the irreducible facts that must exist before ANY repo is
cloned: who the user is. Everything else (project state, cross-cutting
conventions) lives in a repo's `.claude/memory` (tier-1) or the claude-hub
subtree (tier-2) and is reached via `[[hub:name]]` / `[[scope:name]]` links.
Nix seeds this folder ONLY if absent; once present, Claude owns it.

## Who the user is

- [user-profile-senior-dev](user-profile-senior-dev.md) — senior dev profile, working style, how to collaborate.
```

- [ ] **Step 2: Create the seed `user-profile-senior-dev.md` (profile only)**

Copy the irreducible profile fact. Source it from the hub's existing note so the two agree; trim to the machine-universal core (no repo-specific chantier references).

`nix-darwin-home/modules/home-manager/claude-code.d/memory-seed/user-profile-senior-dev.md`:

```markdown
---
name: user-profile-senior-dev
description: "Who the user is and how to collaborate — senior dev, orthogonal-axes thinker, domain-model load-bearing, deepest pain is errors-as-logs. Continuity lives in memory files only."
metadata:
  node_type: memory
  type: user
---

Senior developer (~40 years' experience). Thinks in orthogonal axes; the domain
model is load-bearing in every design discussion. Deepest recurring pain:
errors deported into logs instead of handled at the boundary. Collaborate by
going deep, justifying decisions, and conceding honestly when wrong — not by
deferring. French citizen; writes in English (still developing) — restate
unclear phrasing back briefly to confirm intent, don't over-correct.

Continuity across sessions exists ONLY through memory files: conversations are
ephemeral and per-worktree. When something is worth remembering, write it to
the appropriate tier (home = profile, repo = project, hub = cross-cutting).
```

- [ ] **Step 3: Add the `seedMemory` option + activation to `claude-code.nix`**

In `nix-darwin-home/modules/home-manager/claude-code.nix`, add a `seedMemory` option alongside `seed`, and the activation alongside `claudeCodeSeed`. Use a store-copied directory (the seed has multiple files), guarded seed-if-absent on the DIRECTORY.

Add to the `let … in` block (near `seedFile`):

```nix
  # Bootstrap seed for ~/.claude/memory/. Copied into place ONLY when the
  # directory is absent (fresh machine). Once present, Claude owns it —
  # /memory edits write back and we never overwrite. Minimal by design:
  # carries only the profile (the irreducible-without-a-repo fact); all
  # cross-cutting memory lives in the claude-hub subtree, project memory in
  # each repo's .claude/memory.
  seedMemoryDir = ./claude-code.d/memory-seed;
```

Add the activation inside `config = mkIf cfg.enable { … }`, after `claudeCodeSeed`:

```nix
    home.activation.claudeCodeSeedMemory = lib.hm.dag.entryAfter [ "writeBoundary" ] ''
      claudeMemory="$HOME/.claude/memory"
      if [ ! -e "$claudeMemory" ]; then
        $VERBOSE_ECHO "Seeding fresh Claude Code memory/ from flake (profile only)"
        $DRY_RUN_CMD mkdir -p "$claudeMemory"
        $DRY_RUN_CMD cp -R ${seedMemoryDir}/. "$claudeMemory/"
        $DRY_RUN_CMD chmod -R u+w "$claudeMemory"
      else
        $VERBOSE_ECHO "Claude Code memory/ exists — leaving it untouched"
      fi
    '';
```

(`chmod -R u+w` because nix-store sources are read-only; Claude must be able to write back.)

- [ ] **Step 4: Evaluate the module (dry activation) to prove it builds and is seed-if-absent**

Run from the nix-darwin-home checkout. Build the activation without switching:

```bash
cd /private/var/lib/git/nxmatic/nix-darwin-home
# adjust the flake attr to the machine; dry-run shows the activation script without applying
darwin-rebuild build --flake .#"$(hostname -s)" 2>&1 | tail -20
```

Expected: build SUCCEEDS; no eval error. (This is a build/dry step — it does not mutate `~/.claude`. Actual `darwin-rebuild switch` is the USER's gesture, per the "operations that change the live system are run by the user" rule — propose it, do not run it.)

- [ ] **Step 5: Verify the seed-if-absent guard logic by inspection**

Confirm the guard matches the proven `claudeCodeSeed` shape (absent → seed → stop; present → untouched):

```bash
grep -n 'claudeCodeSeedMemory' -A12 modules/home-manager/claude-code.nix
```

Expected: the `if [ ! -e "$claudeMemory" ]` branch seeds; the `else` branch only echoes. No `rm`, no unconditional copy.

- [ ] **Step 6: Commit (in nix-darwin-home)**

```bash
cd /private/var/lib/git/nxmatic/nix-darwin-home
git add modules/home-manager/claude-code.nix modules/home-manager/claude-code.d/memory-seed/
git commit -m "feat(claude-code): seed ~/.claude/memory with minimal profile if absent

Mirror the claudeCodeSeed pattern: seed-if-absent, never overwrite. Home tier
of the three-tier memory cascade — profile only; cross-cutting lives in the
claude-hub subtree, project memory in each repo.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Fix the stale slug-encoding documentation (stop re-teaching the bug)

**Files:**
- Modify: `.claude/hub/skills/track-claude-memory-in-repo/SKILL.md` (step 1 slug derivation; step 7 helper source)

**Interfaces:**
- Consumes: the canonical helper location from Task 1 (`.claude/hub/bin/link-memory.sh`).
- Produces: a SKILL.md that teaches the correct slug encoding and points at the canonical helper. Terminal documentation task; nothing depends on it.

- [ ] **Step 1: Fix the slug derivation in SKILL.md step 1**

The skill currently teaches `SLUG=$(pwd | sed 's:/:-:g')` — the original bug. Replace the `/`-only encoding with `/` AND `.`, and derive from the worktree root.

In `.claude/hub/skills/track-claude-memory-in-repo/SKILL.md`, change the step-1 example:

Old:
```
   `/private/var/lib/git/nxmatic/rke2lab` →
   `-private-var-lib-git-nxmatic-rke2lab`). Derive it from the repo root, don't guess:
   `SLUG=$(pwd | sed 's:/:-:g')`. If no `memory/` exists yet, tell the user there's nothing to
```

New:
```
   `/private/var/lib/git/nxmatic/rke2lab.d/main` →
   `-private-var-lib-git-nxmatic-rke2lab-d-main`, note `.d` → `-d`). Derive it from the
   worktree root, encoding BOTH `/` and `.`:
   `SLUG=$(git rev-parse --show-toplevel | sed 's:[/.]:-:g')`. If no `memory/` exists yet, tell the user there's nothing to
```

- [ ] **Step 2: Fix step 7 to point at the canonical hub helper**

The skill's step 7 says "Copy `link-memory.sh` (next to this SKILL.md) into the repo's `.claude/bin/`." That copy no longer exists (Task 1 deleted it) and the anti-pattern is duplication. Point at the canonical hub helper instead.

Old:
```
7. **Drop the portable helper.** Copy `link-memory.sh` (next to this SKILL.md) into the repo's
   `.claude/bin/`. It recreates the home→repo symlink on a fresh clone (the symlink target is
   absolute, so it does not survive cloning to a new machine — only the content does). Ensure
   `.claude/.gitignore` does not exclude it (repos often ignore `[Bb]in/`; add `!bin/` +
   `!bin/*.sh` if so).
```

New:
```
7. **Use the canonical helper.** The re-link helper lives once, in the claude-hub subtree at
   `.claude/hub/bin/link-memory.sh`, and travels with the subtree — do NOT copy it per repo.
   It recreates the `$CLAUDE_CONFIG_DIR/projects/<slug>/memory` → `<repo>/.claude/memory`
   symlink on a fresh clone/worktree (the target is absolute, so it does not survive cloning —
   only the content does). Run it with cwd inside the worktree:
   `bash .claude/hub/bin/link-memory.sh`. It encodes the slug correctly (`/` and `.` → `-`) and
   anchors under `${CLAUDE_CONFIG_DIR:-$HOME/.claude}`.
```

- [ ] **Step 3: Verify no stale `s:/:-:g` slug encoding remains anywhere in tracked docs/scripts**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/chore/claude-memory-cascade
grep -rn "s:/:-:g" --include='*.md' --include='*.sh' . | grep -v '\.git/'
```

Expected: empty. (Any survivor that legitimately documents the OLD bug as history — e.g. in `claude-memory-cascade-state.md` or `claude-auto-memory-mechanics.md` — is acceptable ONLY if it is clearly framed as "the bug was", not as an instruction. Inspect each hit; fix instructions, leave history.)

- [ ] **Step 4: Commit**

```bash
git add .claude/hub/skills/track-claude-memory-in-repo/SKILL.md
git commit -m "docs(skill): fix slug encoding (/ and .) + point at canonical hub helper

track-claude-memory-in-repo taught the original s:/:-:g bug and a per-repo
helper copy. Encode both / and . from the worktree root; reference the single
canonical .claude/hub/bin/link-memory.sh.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage** (each §11 in-scope item → task):

- config-home wrapper model (kept as-is) → no change needed; documented in Task 1/2 contracts. ✓
- Option B reading model → already in place (the migrated MEMORY.md indexes); no wiring task needed. ✓ (content migration DONE per spec §8)
- slug invariant → Global Constraints + Task 2 Step 4 (leak check). ✓
- lifecycle + ephemeral-conversation principle → captured in spec; no code deliverable (it's a working convention). ✓
- sidebar-purge-on-removal corollary → spec §6 explicitly defers the mechanism ("capture now, validate in real life"); OUT OF SCOPE for this plan, correctly omitted. ✓
- `link-memory.sh` two-axis fix + dedup → Task 1 (fix already committed; dedup is the work). ✓
- per-worktree wiring → Task 2. ✓
- minimal nix home seed → Task 3. ✓

**Gap found + closed:** the spec's §9 NOTE + the stale SKILL.md would keep teaching `s:/:-:g`. Added Task 4 to fix the documentation, otherwise the next person re-introduces the bug.

**2. Placeholder scan:** no TBD/TODO; every code step shows exact commands + expected output. The one deliberate deferral (sidebar purge) is the spec's own out-of-scope, not a placeholder.

**3. Type/path consistency:** slug encoding is `sed 's:[/.]:-:g'` in every task (Global Constraints, Task 1 contract, Task 2 Steps 1/2/4, Task 4 Step 1). Canonical helper path `.claude/hub/bin/link-memory.sh` is identical across Task 1 (produces), Task 2 (invokes), Task 4 (references). Config-home anchor `${CLAUDE_CONFIG_DIR:-$HOME/.claude}` consistent. No naming drift.

**Note on FS invariant:** every FS-touching step (Task 1 Step 5, Task 2 Steps 2-4, Task 3 Steps 3-4) operates at or above the slug `memory` symlink, or on tracked repo files / the nix store — none reaches inside a slug directory. Verified against the ABSOLUTE constraint.
