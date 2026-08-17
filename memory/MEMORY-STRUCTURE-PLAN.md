# Memory Restructure (DAG: meta-hub + per-repo) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split Claude's mixed memory pile into a `claude-memory` meta-repo hub (cross-cutting + registry-map) plus per-repo memories, linked as a traversable DAG with scoped `[[links]]`.

**Architecture:** New git repo `claude-memory` becomes the auto-loaded session root. Cross-cutting + cross-repo memory moves there; rke2lab keeps only rke2lab-specific notes. Cross-repo links get re-scoped `[[hub:...]]`/`[[repo:...]]`. The session-path symlink is repointed so launching from `claude-memory` auto-loads the hub.

**Tech Stack:** git, Claude file-memory (`.claude/memory/MEMORY.md` index + topic files), the native `autoMemoryDirectory` setting (pins auto-memory read+write to a tracked dir; replaced the old symlink + deleted `link-memory.sh`), the `track-claude-memory-in-repo` skill pattern.

**Source spec:** `MEMORY-STRUCTURE-SPEC.md` (same dir).

**Hard constraint:** rke2lab has a LIVE parallel Claude session (worktree `feature/problem-oriented-provenance`). rke2lab's `.claude/memory/` is git-tracked. Do NOT commit rke2lab pruning without coordinating; stage it as the LAST tasks and flag for the user. The hub creation + hub-file population (Tasks 1–6) touch ONLY the new repo and are safe to do immediately.

---

## File Structure

**New repo `claude-memory`:**
- `/private/var/lib/git/nxmatic/claude-memory/.claude/memory/MEMORY.md` — hub index (cross-cutting entries + registry-map)
- `.../memory/<cross-cutting>.md` — profile, conventions, principles, universal gotchas (moved from rke2lab)
- `.../memory/<cross-repo>.md` — maven fleet, distributed-assets, global-wip (moved from rke2lab)
- `/private/var/lib/git/nxmatic/claude-memory/.claude/memory/MEMORY-STRUCTURE-SPEC.md` + this plan — moved here (the work belongs to the hub)

**Modified (LAST, coordinated):**
- `rke2lab/.claude/memory/MEMORY.md` — pruned to rke2lab-only
- rke2lab cross-repo notes — re-scoped links, or removed if moved

**Symlink:**
- `~/.claude/projects/-private-var-lib-git-nxmatic-claude-memory/memory` → `claude-memory/.claude/memory` (new, for auto-load when launching from there)

### File classification (from spec, frozen here)

**MOVE TO HUB — cross-cutting:**
`user-profile-senior-dev`, `working-style-narrate-progress`, `works-best-from-concrete-code`, `error-handling-layered-contract`, `docs-diagrams-not-java`, `sequential-no-compat-workflow`, `shared-artifacts-in-english`, `worktree-per-conversation`, `branch-namespaces`, `superpowers-assets-in-wip`, `wip-guard-hooks`, `diagram-preview-file`, `brainstorm-vocabulary-view-first`, `bedrock-compaction-issue`, `shared-test-fixtures-module`, `local-classes-pattern`, `review-scope-backlog`, `model-substrate-alignment`, `specialist-as-ledger-northstar`

**MOVE TO HUB — cross-repo:**
`maven-github-token-resolution`, `maven-extension-ecosystem`, `maven4-status-backlog`, `pme-5.3-manipulated-pom-port`, `global-wip-guard-hooks-state`, `claude-distributed-assets-topic`, `docrepo-dag-state`

**ALSO TO HUB — orphan, cross-cutting:** `feedback_verify_working_directory` (from java-systemd session dir `~/.claude/projects/-private-var-lib-git-thjomnx-java-systemd/memory/`)

**STAY IN rke2lab (project-specific):** all `doctor`/health/medical/referral/intervention/master-provisioning/runbook/preview-whatif/cohort/efficacy notes, plus `manifests-doc-consolidation`, `terminology-refactor-state`, `refactor-pipeline-candidates`, `package-private-sweep`, `seed-vcluster`, `manifest-registrars-enum-refactor`, `domain-registry-abstraction`, `config-restructuring-state`, `dsl-unification-topic`, `build-verification-gotchas`, `bdd-jgiven-test-strategy`, `seeded-history-automation-api`, `task14-readonly-preview-integration`, `medical-record-*`, `healthsystem-*`, `cohort-correlation-spike`, `manifests-doc-consolidation`

---

## Task 1: Create the claude-memory repo skeleton

**Files:**
- Create: `/private/var/lib/git/nxmatic/claude-memory/` (git repo)
- Create: `/private/var/lib/git/nxmatic/claude-memory/.claude/memory/`
- Create: `/private/var/lib/git/nxmatic/claude-memory/.gitignore`
- Create: `/private/var/lib/git/nxmatic/claude-memory/README.md`

- [ ] **Step 1: Create repo + memory dir**

```bash
mkdir -p /private/var/lib/git/nxmatic/claude-memory/.claude/memory
cd /private/var/lib/git/nxmatic/claude-memory
git init -q
git branch -m main
```

- [ ] **Step 2: Add README + .gitignore**

README.md content:
```markdown
# claude-memory

Claude's cross-cutting memory hub. The root workspace of every Claude session.

- `.claude/memory/MEMORY.md` — auto-loaded hub index: cross-cutting facts
  (profile, conventions, principles, universal gotchas) + a registry-map of
  active chantiers across all repos.
- Project-specific memory lives in each project repo's own `.claude/memory/`,
  loaded on demand.
- Links form a traversable DAG: `[[name]]` intra-repo, `[[scope:name]]`
  cross-repo (scope = a repo name or `hub`).

See `.claude/memory/MEMORY-STRUCTURE-SPEC.md` for the full design.
```

.gitignore content:
```
.claude/memory/diag-*.svg
.claude/claude-preview.adoc
.asciidoctorconfig
```

- [ ] **Step 3: Verify**

Run: `cd /private/var/lib/git/nxmatic/claude-memory && git status && ls -la .claude/memory`
Expected: clean git repo on `main`, empty `.claude/memory/`, README + .gitignore untracked.

- [ ] **Step 4: Commit skeleton**

```bash
cd /private/var/lib/git/nxmatic/claude-memory
git add README.md .gitignore
git -c user.name=nxmatic -c user.email=nxmatic@users.noreply.github.com commit -q -m "chore: bootstrap claude-memory hub skeleton"
```

---

## Task 2: Move the spec + plan into the hub (the work belongs to it)

**Files:**
- Move: `rke2lab/.claude/memory/MEMORY-STRUCTURE-SPEC.md` → `claude-memory/.claude/memory/`
- Move: `rke2lab/.claude/memory/MEMORY-STRUCTURE-PLAN.md` (this file) → `claude-memory/.claude/memory/`

- [ ] **Step 1: Copy both files to the hub**

```bash
SRC=/private/var/lib/git/nxmatic/rke2lab/.claude/memory
DST=/private/var/lib/git/nxmatic/claude-memory/.claude/memory
cp "$SRC/MEMORY-STRUCTURE-SPEC.md" "$SRC/MEMORY-STRUCTURE-PLAN.md" "$DST/"
```

- [ ] **Step 2: Verify both landed**

Run: `ls /private/var/lib/git/nxmatic/claude-memory/.claude/memory/MEMORY-STRUCTURE-*.md`
Expected: both files listed.

- [ ] **Step 3: Commit to hub**

```bash
cd /private/var/lib/git/nxmatic/claude-memory
git add .claude/memory/MEMORY-STRUCTURE-SPEC.md .claude/memory/MEMORY-STRUCTURE-PLAN.md
git -c user.name=nxmatic -c user.email=nxmatic@users.noreply.github.com commit -q -m "docs: move memory-structure spec + plan into the hub"
```

(rke2lab copies removed in Task 8, the coordinated pruning task.)

---

## Task 3: Move the cross-cutting files to the hub

**Files:** the 19 cross-cutting `.md` files listed above + their existence in rke2lab.

- [ ] **Step 1: Copy cross-cutting files to hub**

```bash
SRC=/private/var/lib/git/nxmatic/rke2lab/.claude/memory
DST=/private/var/lib/git/nxmatic/claude-memory/.claude/memory
for f in user-profile-senior-dev working-style-narrate-progress works-best-from-concrete-code \
  error-handling-layered-contract docs-diagrams-not-java sequential-no-compat-workflow \
  shared-artifacts-in-english worktree-per-conversation branch-namespaces superpowers-assets-in-wip \
  wip-guard-hooks diagram-preview-file brainstorm-vocabulary-view-first bedrock-compaction-issue \
  shared-test-fixtures-module local-classes-pattern review-scope-backlog model-substrate-alignment \
  specialist-as-ledger-northstar; do
  cp "$SRC/$f.md" "$DST/$f.md"
done
```

- [ ] **Step 2: Verify count (19 files)**

Run: `cd /private/var/lib/git/nxmatic/claude-memory/.claude/memory && ls user-profile-senior-dev.md working-style-narrate-progress.md works-best-from-concrete-code.md error-handling-layered-contract.md docs-diagrams-not-java.md sequential-no-compat-workflow.md shared-artifacts-in-english.md worktree-per-conversation.md branch-namespaces.md superpowers-assets-in-wip.md wip-guard-hooks.md diagram-preview-file.md brainstorm-vocabulary-view-first.md bedrock-compaction-issue.md shared-test-fixtures-module.md local-classes-pattern.md review-scope-backlog.md model-substrate-alignment.md specialist-as-ledger-northstar.md 2>&1 | wc -l`
Expected: `19` (no "No such file" errors).

- [ ] **Step 3: Commit**

```bash
cd /private/var/lib/git/nxmatic/claude-memory
git add .claude/memory/*.md
git -c user.name=nxmatic -c user.email=nxmatic@users.noreply.github.com commit -q -m "feat: migrate cross-cutting memory to hub (19 files)"
```

---

## Task 4: Move the cross-repo files to the hub

**Files:** the 7 cross-repo `.md` files.

- [ ] **Step 1: Copy cross-repo files to hub**

```bash
SRC=/private/var/lib/git/nxmatic/rke2lab/.claude/memory
DST=/private/var/lib/git/nxmatic/claude-memory/.claude/memory
for f in maven-github-token-resolution maven-extension-ecosystem maven4-status-backlog \
  pme-5.3-manipulated-pom-port global-wip-guard-hooks-state claude-distributed-assets-topic \
  docrepo-dag-state; do
  cp "$SRC/$f.md" "$DST/$f.md"
done
```

- [ ] **Step 2: Verify (7 files)**

Run: `cd /private/var/lib/git/nxmatic/claude-memory/.claude/memory && ls maven-github-token-resolution.md maven-extension-ecosystem.md maven4-status-backlog.md pme-5.3-manipulated-pom-port.md global-wip-guard-hooks-state.md claude-distributed-assets-topic.md docrepo-dag-state.md 2>&1 | wc -l`
Expected: `7`.

- [ ] **Step 3: Commit**

```bash
cd /private/var/lib/git/nxmatic/claude-memory
git add .claude/memory/*.md
git -c user.name=nxmatic -c user.email=nxmatic@users.noreply.github.com commit -q -m "feat: migrate cross-repo topics to hub (maven fleet, docrepo, wip-guard, distributed-assets)"
```

---

## Task 5: Fold the java-systemd orphan into the hub

**Files:**
- Source: `~/.claude/projects/-private-var-lib-git-thjomnx-java-systemd/memory/feedback_verify_working_directory.md`
- Create: `claude-memory/.claude/memory/feedback-verify-working-directory.md` (renamed kebab-case for consistency)

- [ ] **Step 1: Copy + rename the orphan feedback**

```bash
SRC=~/.claude/projects/-private-var-lib-git-thjomnx-java-systemd/memory
DST=/private/var/lib/git/nxmatic/claude-memory/.claude/memory
cp "$SRC/feedback_verify_working_directory.md" "$DST/feedback-verify-working-directory.md"
```

- [ ] **Step 2: Verify content is the CWD-verification feedback**

Run: `grep -i "working directory" /private/var/lib/git/nxmatic/claude-memory/.claude/memory/feedback-verify-working-directory.md | head -1`
Expected: a line about verifying the working directory before shell commands.

- [ ] **Step 3: Commit**

```bash
cd /private/var/lib/git/nxmatic/claude-memory
git add .claude/memory/feedback-verify-working-directory.md
git -c user.name=nxmatic -c user.email=nxmatic@users.noreply.github.com commit -q -m "feat: fold java-systemd orphan (verify-working-directory feedback) into hub"
```

---

## Task 6: Write the hub MEMORY.md (index + registry-map)

**Files:**
- Create: `claude-memory/.claude/memory/MEMORY.md`

- [ ] **Step 1: Write the hub index**

Content (sections: cross-cutting entries one-line each + a registry-map of active chantiers per repo). Use `[[name]]` for hub-local links, `[[repo:name]]` for project-repo links:

```markdown
# Memory index — hub (claude-memory)

The cross-cutting hub, auto-loaded as the session root. One line per entry (<~180 chars); detail lives in the file. Project-specific memory lives in each repo's own .claude/memory (loaded on demand). Links: [[name]] hub-local, [[scope:name]] cross-repo (scope = repo name or hub).

## Registry-map — active chantiers per repo

- **rke2lab** — doctor/health system (runbook, intervention-provenance EXECUTING), manifests, config restructuring. See [[rke2lab:intervention-provenance-state]], [[rke2lab:runbook-doctor-state]].
- **maven fleet** (cross-repo: pme + maven-devenv-extension + build-cache + rke2lab consumer) — [[maven-github-token-resolution]], [[maven-extension-ecosystem]], [[pme-5.3-manipulated-pom-port]], [[maven4-status-backlog]].
- **docrepo-dag-wip** — [[docrepo-dag-state]] (V1 spec written, own repo).
- **nix-darwin-home** — [[global-wip-guard-hooks-state]] (wip-guard shipped, 2 steps pending).

## Cross-cutting: who the user is + how to work

- [[user-profile-senior-dev]] — ~40 yrs; orthogonal axes, domain-model load-bearing, deepest pain = errors-as-logs; go deep + justify + concede honestly.
- [[working-style-narrate-progress]] — narrate intent before each tool batch; silent gaps read as stuck.
- [[works-best-from-concrete-code]] — user decides best seeing real impl; get to runnable code fast.
- [[error-handling-layered-contract]] — Optional=nothing-here, exception=went-wrong; leaf throws, aggregator addSuppressed, caller decides.
- [[docs-diagrams-not-java]] — repo docs/specs use prose + C4/UML Mermaid, NEVER Java snippets.
- [[sequential-no-compat-workflow]] — one topic at a time; NEVER backwards-compat; delete old paths same change.
- [[shared-artifacts-in-english]] — docs/comments/commits en-US, never French.
- [[feedback-verify-working-directory]] — verify CWD before shell commands (sandbox can be anywhere).

## Cross-cutting: git / workspace / build

- [[worktree-per-conversation]] — PROMOTED to global CLAUDE.md rule; each conversation its OWN worktree, main READ-ONLY, base=fresh.
- [[branch-namespaces]] — kind-prefixed branches (feature/refactor/spike/); wip-guard keys off the wip/ DIR.
- [[superpowers-assets-in-wip]] — plans+specs in wip/superpowers/, NOT docs/; kept off main by wip-guard.
- [[wip-guard-hooks]] — .githooks pre-commit/pre-push block wip/ reaching protected branches.
- [[bedrock-compaction-issue]] — auto-compaction fails; warn at 80k/120k tokens.

## Cross-cutting: design + doc conventions

- [[shared-test-fixtures-module]] — cross-module fixtures in a *-testkit module (src/main/java, scope=test); never duplicate/test-jar.
- [[local-classes-pattern]] — local classes in methods (not inner) when single-use & simple.
- [[review-scope-backlog]] — quality reviewers use strict-diff scope; pre-existing code = non-blocking backlog at END.
- [[diagram-preview-file]] — present mermaid via in-workspace .claude/claude-preview.adoc; kroki bioskop-nixos.local:8000; safe dialect = solid arrows, nude labels, flowchart.
- [[brainstorm-vocabulary-view-first]] — lead a new design with a code-faithful vocabulary diagram (Diagram 0).

## North-stars (design principles, active)

- [[model-substrate-alignment]] — if a round-trip WRITES through path X but READS bypassing X, the model is mis-fitted (the bypass IS the tell).
- [[specialist-as-ledger-northstar]] — a knowledge-accumulating specialist IS a ledger (memory=a Pulumi stack); holds for drift/intervention subclass, not stateless reactive specialists.

## Cross-repo chantiers (detail)

- [[maven-github-token-resolution]] — credential-command-resolver: Maven core ext resolves <server> password from `@command:gh auth token`; kills ${env.GH_TOKEN} snapshot. Workaround: `GH_TOKEN= git -c credential.helper='!gh auth git-credential' push`.
- [[maven-extension-ecosystem]] — devenv = distributor of forked/patched Maven exts; first payload = build-cache m2e patches.
- [[pme-5.3-manipulated-pom-port]] — ACTIVE: revive "build & attach manipulated POM without overwriting original" onto jboss.pnc.mavenmanipulator 5.3. Bootstrap lever for the fleet.
- [[maven4-status-backlog]] — Maven 4 RC-only, not prod; stay 3.9.x; dedicated deep-dive later.
- [[global-wip-guard-hooks-state]] — global wip-guard shipped to nix-darwin-home; 2 steps pending (rke2lab migration, e2e verify).
- [[claude-distributed-assets-topic]] — this very topic: distributing Claude memory/skills across repos+hosts. SUPERSEDED by the memory-structure work → [[MEMORY-STRUCTURE-SPEC]].
- [[docrepo-dag-state]] — docrepo-dag-wip repo, V1 spec written; OSGi-resolution + P2P doc repository.
```

- [ ] **Step 2: Verify size under cap + links present**

Run: `cd /private/var/lib/git/nxmatic/claude-memory/.claude/memory && wc -c MEMORY.md && grep -c '\[\[' MEMORY.md`
Expected: well under 24400 bytes; ~30 links present.

- [ ] **Step 3: Commit**

```bash
cd /private/var/lib/git/nxmatic/claude-memory
git add .claude/memory/MEMORY.md
git -c user.name=nxmatic -c user.email=nxmatic@users.noreply.github.com commit -q -m "feat: write hub MEMORY.md (cross-cutting index + registry-map)"
```

---

## Task 7: Re-scope cross-repo links in the hub files

**Files:** the hub `.md` files whose `[[links]]` point at rke2lab-specific notes now need `[[rke2lab:...]]`.

- [ ] **Step 1: Find cross-repo links needing a scope in hub files**

Run:
```bash
cd /private/var/lib/git/nxmatic/claude-memory/.claude/memory
grep -rno '\[\[[a-z0-9-]*\]\]' *.md | grep -vE 'MEMORY.md' | sort -u
```
Expected: a list of `[[name]]` links. For each, decide: does the target file live in the hub (leave bare) or in rke2lab (re-scope to `[[rke2lab:name]]`)?

- [ ] **Step 2: Re-scope rke2lab-target links**

For each hub file linking a note that STAYS in rke2lab (e.g. `intervention-provenance-state`, `runbook-doctor-state`, `doctor-*`, `config-restructuring-state`), rewrite `[[name]]` → `[[rke2lab:name]]`. Example for `model-substrate-alignment.md` and `specialist-as-ledger-northstar.md` (they link `intervention-provenance-state`):

```bash
cd /private/var/lib/git/nxmatic/claude-memory/.claude/memory
sed -i '' 's/\[\[intervention-provenance-state\]\]/[[rke2lab:intervention-provenance-state]]/g' model-substrate-alignment.md specialist-as-ledger-northstar.md
```

(Repeat per file for each rke2lab-target link surfaced in Step 1. Hub-internal links — e.g. `[[maven-extension-ecosystem]]` — stay bare.)

- [ ] **Step 3: Verify no bare link points at a non-hub file**

Run:
```bash
cd /private/var/lib/git/nxmatic/claude-memory/.claude/memory
for l in $(grep -rho '\[\[[a-z0-9-]*\]\]' *.md | tr -d '[]' | sort -u); do
  [ -f "$l.md" ] || echo "DANGLING bare link (should be scoped or is in another repo): [[$l]]"
done
```
Expected: every reported dangling link is one that legitimately lives in another repo and should now be `[[repo:...]]`. Fix any that slipped.

- [ ] **Step 4: Commit**

```bash
cd /private/var/lib/git/nxmatic/claude-memory
git add .claude/memory/*.md
git -c user.name=nxmatic -c user.email=nxmatic@users.noreply.github.com commit -q -m "fix: re-scope cross-repo links to [[rke2lab:...]] in hub files"
```

---

## Task 8: Wire the session symlink + launch convention

**Files:**
- Create symlink: `~/.claude/projects/-private-var-lib-git-nxmatic-claude-memory/memory` → hub memory.

- [ ] **Step 1: Create the session-path symlink for claude-memory**

```bash
SESS=~/.claude/projects/-private-var-lib-git-nxmatic-claude-memory
mkdir -p "$SESS"
ln -sfn /private/var/lib/git/nxmatic/claude-memory/.claude/memory "$SESS/memory"
```

- [ ] **Step 2: Verify the symlink resolves to the hub**

Run: `readlink ~/.claude/projects/-private-var-lib-git-nxmatic-claude-memory/memory && ls ~/.claude/projects/-private-var-lib-git-nxmatic-claude-memory/memory/MEMORY.md`
Expected: points to `…/claude-memory/.claude/memory`, hub MEMORY.md listed.

- [ ] **Step 3: Document the launch convention in the hub README**

Append to `claude-memory/README.md`:
```markdown

## Launch convention

Start Claude sessions from `/private/var/lib/git/nxmatic/claude-memory` (the hub
auto-loads). Add the project repos you'll touch as additional working
directories. Claude filters the registry-map to those repos and asks which
entry point to attack; per-repo memory loads on demand as the conversation goes.
```

```bash
cd /private/var/lib/git/nxmatic/claude-memory
git add README.md
git -c user.name=nxmatic -c user.email=nxmatic@users.noreply.github.com commit -q -m "docs: launch convention (root sessions at claude-memory)"
```

---

## Task 9 (COORDINATED — needs user go-ahead): Prune rke2lab memory

**⚠ rke2lab has a LIVE parallel session + git-tracked memory. Do NOT run until the user confirms the parallel session is idle or has merged. This task only REMOVES files now duplicated in the hub and prunes rke2lab's MEMORY.md to rke2lab-only.**

**Files:**
- Delete from rke2lab: the 19 cross-cutting + 7 cross-repo files + spec + plan (now in hub).
- Modify: `rke2lab/.claude/memory/MEMORY.md` — drop hub-migrated entries, keep rke2lab-only.

- [ ] **Step 1: Confirm every to-delete file exists in the hub first (no data loss)**

```bash
HUB=/private/var/lib/git/nxmatic/claude-memory/.claude/memory
RKE=/private/var/lib/git/nxmatic/rke2lab/.claude/memory
for f in user-profile-senior-dev working-style-narrate-progress works-best-from-concrete-code \
  error-handling-layered-contract docs-diagrams-not-java sequential-no-compat-workflow \
  shared-artifacts-in-english worktree-per-conversation branch-namespaces superpowers-assets-in-wip \
  wip-guard-hooks diagram-preview-file brainstorm-vocabulary-view-first bedrock-compaction-issue \
  shared-test-fixtures-module local-classes-pattern review-scope-backlog model-substrate-alignment \
  specialist-as-ledger-northstar maven-github-token-resolution maven-extension-ecosystem \
  maven4-status-backlog pme-5.3-manipulated-pom-port global-wip-guard-hooks-state \
  claude-distributed-assets-topic docrepo-dag-state; do
  [ -f "$HUB/$f.md" ] || echo "MISSING IN HUB (do NOT delete from rke2lab): $f"
done
echo "check complete"
```
Expected: only "check complete" — no MISSING lines. If any MISSING, STOP and re-run the relevant move task.

- [ ] **Step 2: Remove the migrated files from rke2lab (git rm)**

```bash
cd /private/var/lib/git/nxmatic/rke2lab/.claude/memory
git rm -q user-profile-senior-dev.md working-style-narrate-progress.md works-best-from-concrete-code.md \
  error-handling-layered-contract.md docs-diagrams-not-java.md sequential-no-compat-workflow.md \
  shared-artifacts-in-english.md worktree-per-conversation.md branch-namespaces.md superpowers-assets-in-wip.md \
  wip-guard-hooks.md diagram-preview-file.md brainstorm-vocabulary-view-first.md bedrock-compaction-issue.md \
  shared-test-fixtures-module.md local-classes-pattern.md review-scope-backlog.md model-substrate-alignment.md \
  specialist-as-ledger-northstar.md maven-github-token-resolution.md maven-extension-ecosystem.md \
  maven4-status-backlog.md pme-5.3-manipulated-pom-port.md global-wip-guard-hooks-state.md \
  claude-distributed-assets-topic.md docrepo-dag-state.md MEMORY-STRUCTURE-SPEC.md MEMORY-STRUCTURE-PLAN.md
```

- [ ] **Step 3: Prune rke2lab MEMORY.md to rke2lab-only**

Rewrite `rke2lab/.claude/memory/MEMORY.md`: keep ONLY the rke2lab-specific entries (doctor/health/manifests/config/build chantiers). Drop the cross-cutting + cross-repo lines (now in hub). Add a header line pointing at the hub:
```markdown
# Memory index — rke2lab

Project-specific memory. Cross-cutting facts + cross-repo chantiers live in the
hub ([[hub:MEMORY]] at /private/var/lib/git/nxmatic/claude-memory). One line per
entry; detail in the file. [[name]] = rke2lab-local, [[hub:name]] = hub.
```
(Keep the rke2lab-only entries from the current MEMORY.md verbatim, re-scoping any link that now points at a hub note to `[[hub:...]]`.)

- [ ] **Step 4: Verify rke2lab memory is rke2lab-only + nothing dangling**

Run:
```bash
cd /private/var/lib/git/nxmatic/rke2lab/.claude/memory
ls *.md | grep -E 'maven|docrepo|profile|worktree|error-handling' && echo "LEAK: hub files still here" || echo "clean: rke2lab-only"
```
Expected: `clean: rke2lab-only`.

- [ ] **Step 5: Stage for the user to commit (do NOT auto-commit rke2lab)**

Leave the changes staged. Tell the user: rke2lab memory pruned + MEMORY.md rewritten, staged but NOT committed — commit when the parallel session is clear, since rke2lab memory is shared.

---

## Self-review notes

- **Spec coverage:** model (Tasks 1,6,8), per-repo split (Tasks 3,4,9), scoped DAG links (Tasks 6,7), registry-map (Task 6), migration of 56 files (Tasks 3,4,5,9), java-systemd orphan (Task 5), symlink/launch (Task 8), coordination constraint (Task 9 gated). ✓
- **No data loss:** Task 9 deletes from rke2lab ONLY after Task 9 Step 1 confirms each file exists in the hub. ✓
- **GitHub push:** intentionally NOT in this plan — creating the remote `nxmatic/claude-memory` + push is a separate outward-facing step the user authorises later.
- **Open item (updated 2026-08-14):** `autoMemoryDirectory` (an absolute path in `settings.local.json`) now replaces Task 8's manual symlink outright — the `track-claude-memory-in-repo` skill was rewritten around it and `link-memory.sh` is deleted. Revisit Task 8 (and the line-7 "session-path symlink repoint") to point the setting at the memory dir instead of symlinking.
