# OSGi aggregator re-layout — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-lay-out `osgi/` from a flat 17-entry `<modules>` into 3 nature-groups (`foundation/`, `runtime/`, `domains/`) + the 2 Maven parents, then apply 2 of the 3 renames (`runtime`→`runtime-host`, `gateway`→`world-gateway`). The third rename (`jgiven`→`pipeline` dissolution) is DEFERRED (see Global Constraints).

**Architecture:** Pure `git mv` relocation first (transparent to dependents — Maven resolves by `artifactId`, not path; only each moved pom's `<parent><relativePath>` shifts `+1 ../` per level added), one group per task, each ending in a green full-reactor build. Then the 2 renames as separate verifiable tasks (each touches a small, enumerated consumer set). No code logic changes; no module fusion.

**Tech Stack:** Maven multi-module (reactor-only resolution, `-am`), bnd-maven-plugin (OSGi bundles), flox JDK 25 toolchain, `maven-embed-staging-ext` (build-time staging gates read jars off disk → a FULL `package` is required to keep them green).

## Global Constraints

- **Spec of record:** `docs/architecture/osgi/osgi-aggregator-layout-spec.adoc` (§3 target layout, §5.1–§5.7 sub-decisions, §6 migration mechanics). Twin prompt §B: `docs/architecture/osgi/osgi-aggregator-layout-spec.prompt`.
- **No dedicated worktree** — implement directly in `feature/cluster-edge` (user decision 2026-06-30). The prompt §B's worktree mandate assumed a *concurrent* 2B session on `gateway-port`; 2B is now integrated, so there is no concurrent mutator. Base is `feature/cluster-edge` — `osgi/gateway`, `world-gateway`, and the recent domains exist ONLY there (`origin/main` has neither).
- **jgiven dissolution (§5.4) is DEFERRED** — this increment relocates `osgi/jgiven/` AS-IS to `osgi/foundation/jgiven/` (4 modules + their aggregator, unchanged exports). The spec self-contradicts here: §5.4/§3/§6 say "dissolve jgiven into pipeline" but §8 says "Aucune fusion de modules." The dissolution is a *realm change*, not a layout move: `pipeline` is `type=seam` (system-exported, FLAT) while jGiven enters the framework as an INSTALLED bundle (`JGivenTestkit.installFromClasspath` + `installBundles(WRAP_BSN)`); making the seam export `com.tngtech.jgiven.*` would put jGiven in two realms → `LinkageError` (the exact class our `DUPLICATE_REALM_CLASS` gate forbids). It gets its own increment (the `jgiven-domain-into-pipeline-debt` backlog).
- **Reactor-only resolution** — NEVER `mvn install` project artifacts to `~/.m2`. Sibling modules resolve from the reactor via `-am`. `maven-embed-staging-ext` is the documented exception (RELEASE coord via `.mvn/extensions.xml`); it is NOT moved by this plan.
- **Verify recipe (every task's test):** `flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true`. A `BUILD SUCCESS` with the staging line `7 staging-law violation(s) at WARN … 0 ERROR` (or fewer) is the pass. A path/relativePath mistake fails the reactor at model-building — that IS the failing-test signal. NEVER a partial `-am` build for the final verify (the staging gates read the full dependency closure off disk).
- **sed caveat:** GNU sed under flox rejects BSD `-i ''`; use `perl -0pi -e '...'` for in-place pom edits (per `layout-skeleton-state`).
- **Every module keeps a `<description>`** (repo rule "every module has a description"). New aggregators get one.
- **CLI selectors** use the unprefixed artifactId (`-pl :doctor-core`); the 2 renames change 2 selectors (`:runtime`→`:runtime-host`, `:gateway-port`→`:world-gateway`).
- **Commit trailer:** end every commit message with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## Current state (verified 2026-06-30)

Root aggregator is `pom.xml` (lists `<module>osgi</module>`). `build-parent/pom.xml` is at repo root (pure parent, no `<modules>`). `osgi/pom.xml` `<modules>` has 17 flat entries:

```
bundle-parent, bundle-test-parent, domain-annotations, boot, junit-testkit,
runtime, bench, unitrepo, systemd, manifests, netplan, cluster, gateway,
pipeline, jgiven, doctor, ssh-to-age-edge
```

relativePath patterns (all poms parent ONLY to build-parent/bundle-parent/bundle-test-parent):
- subaggregator under `osgi/` (e.g. `osgi/doctor/pom.xml`): `parent=build-parent rel=../../build-parent/pom.xml`
- leaf under subaggregator (e.g. `osgi/doctor/doctor-core`): `parent=bundle-parent rel=../../bundle-parent/pom.xml`
- flat leaf under `osgi/` (e.g. `osgi/domain-annotations`): `parent=bundle-parent rel=../bundle-parent/pom.xml`
- flat leaf under `osgi/` (e.g. `osgi/runtime`, `osgi/gateway/`): `parent=build-parent rel=../../build-parent/pom.xml`
- already-2-deep test leaf (e.g. `osgi/jgiven/jgiven-testkit`): `parent=build-parent rel=../../../build-parent/pom.xml`

**relativePath rule:** moving a pom DOWN by one directory level adds exactly one `../` to its `<parent><relativePath>`. A subaggregator moved into a group takes all its children down with it → every child also gains one `../`.

---

### Task 1: Create the `domains/` group and move the 6 domains + the orphan edge

**Files:**
- Create: `osgi/domains/pom.xml`
- Move (git mv): `osgi/doctor/` `osgi/cluster/` `osgi/systemd/` `osgi/manifests/` `osgi/netplan/` `osgi/unitrepo/` → under `osgi/domains/`; `osgi/ssh-to-age-edge/` → `osgi/domains/ssh-to-age-edge/` (flat leaf, §5.1/§5.7)
- Modify: every moved pom's `<parent><relativePath>` (+1 `../`); `osgi/pom.xml` `<modules>`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `osgi/domains/pom.xml` (packaging=pom, parent=build-parent, `<name>osgi/domains</name>`) listing the 6 domains + `ssh-to-age-edge`. All domain artifactIds UNCHANGED (dependents elsewhere keep resolving).

- [ ] **Step 1: Create the `domains/` aggregator pom**

Create `osgi/domains/pom.xml` on the `osgi/doctor/pom.xml` template (read it first for the exact header), with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.nxmatic.rke2lab</groupId>
    <artifactId>build-parent</artifactId>
    <version>${revision}</version>
    <relativePath>../../build-parent/pom.xml</relativePath>
  </parent>
  <artifactId>domains</artifactId>
  <packaging>pom</packaging>
  <name>osgi/domains</name>
  <description>OSGi world-model domains (doctor, cluster, systemd, manifests, netplan, unitrepo) and the orphan ssh-to-age edge.</description>
  <modules>
    <module>doctor</module>
    <module>cluster</module>
    <module>systemd</module>
    <module>manifests</module>
    <module>netplan</module>
    <module>unitrepo</module>
    <module>ssh-to-age-edge</module>
  </modules>
</project>
```

(Copy `groupId`/`version`/`revision` convention verbatim from `osgi/doctor/pom.xml`; if `doctor/pom.xml` inherits groupId/version from parent and omits them, omit them here too — match the sibling exactly.)

- [ ] **Step 2: git mv the seven modules**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
for d in doctor cluster systemd manifests netplan unitrepo ssh-to-age-edge; do
  git mv "osgi/$d" "osgi/domains/$d"
done
```

- [ ] **Step 3: Fix relativePaths (+1 `../`) in every moved pom**

Each moved pom dropped one level deeper, so each `<relativePath>` gains one `../`:

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
# subaggregator poms (doctor/pom.xml etc.): ../../build-parent → ../../../build-parent
# leaf poms under them (doctor-core etc.): ../../bundle-parent → ../../../bundle-parent
#                                          ../../bundle-test-parent → ../../../bundle-test-parent
# ssh-to-age-edge (was flat leaf ../bundle-parent or ../../build-parent): +1 each
find osgi/domains -name pom.xml -not -path "*/target/*" -print0 | while IFS= read -r -d '' p; do
  perl -0pi -e 's{<relativePath>\.\./\.\./build-parent/pom\.xml</relativePath>}{<relativePath>../../../build-parent/pom.xml</relativePath>}g;
                 s{<relativePath>\.\./\.\./bundle-parent/pom\.xml</relativePath>}{<relativePath>../../../bundle-parent/pom.xml</relativePath>}g;
                 s{<relativePath>\.\./\.\./bundle-test-parent/pom\.xml</relativePath>}{<relativePath>../../../bundle-test-parent/pom.xml</relativePath>}g' "$p"
done
# ssh-to-age-edge was a FLAT leaf (../bundle-parent OR ../../build-parent) → +1:
perl -0pi -e 's{<relativePath>\.\./bundle-parent/pom\.xml</relativePath>}{<relativePath>../../bundle-parent/pom.xml</relativePath>}g;
               s{<relativePath>\.\./\.\./build-parent/pom\.xml</relativePath>}{<relativePath>../../../build-parent/pom.xml</relativePath>}g' osgi/domains/ssh-to-age-edge/pom.xml
```

⚠️ Verify `ssh-to-age-edge/pom.xml`'s ACTUAL parent before running (read it): if it parents to `bundle-parent` with `../bundle-parent`, the first substitution applies; if to `build-parent` with `../../build-parent`, the second. Confirm by reading, then keep only the matching line.

- [ ] **Step 4: Update the root `osgi/pom.xml` `<modules>`**

Remove the 7 moved entries (`doctor`, `cluster`, `systemd`, `manifests`, `netplan`, `unitrepo`, `ssh-to-age-edge`) and add `<module>domains</module>` in their place. Leave the other 10 entries flat for now.

- [ ] **Step 5: Run the full reactor build — expect green**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | grep -E "BUILD (SUCCESS|FAILURE)|staging-law|ERROR.*\.pom|Non-resolvable|Tests run:" | tail -30
```

Expected: `BUILD SUCCESS`; the staging line still `… at WARN … 0 ERROR`; doctor in-container tests present in a `Tests run:` line. A `Non-resolvable parent POM` or `Could not find artifact` means a relativePath or `<modules>` entry is wrong — fix and re-run before committing.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(osgi): group the 6 domains + ssh-to-age-edge under domains/

Pure relocation (git mv); artifactIds unchanged so dependents resolve untouched.
relativePaths +1 ../ per added level. Root <modules> lists domains/ in place of
the 7 moved entries. Layout-first increment, step 1 of the osgi aggregator spec.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Create the `foundation/` group and move domain-annotations, gateway, pipeline, jgiven

**Files:**
- Create: `osgi/foundation/pom.xml`
- Move (git mv): `osgi/domain-annotations/` → `osgi/foundation/domain-annotations/`; `osgi/gateway/` → `osgi/foundation/gateway/`; `osgi/pipeline/` → `osgi/foundation/pipeline/`; `osgi/jgiven/` → `osgi/foundation/jgiven/`
- Modify: every moved pom's relativePath (+1 `../`); `osgi/pom.xml` `<modules>`

**Interfaces:**
- Consumes: Task 1's root `<modules>` shape (domains already grouped).
- Produces: `osgi/foundation/pom.xml` listing `domain-annotations`, `gateway`, `pipeline`, `jgiven`. All artifactIds UNCHANGED (the renames are Tasks 4–5). `gateway/` and `jgiven/` keep their internal structure (singleton-reduction + dissolution are later/deferred).

- [ ] **Step 1: Create the `foundation/` aggregator pom**

Create `osgi/foundation/pom.xml` (same template as Task 1 Step 1), with `<artifactId>foundation</artifactId>`, `<name>osgi/foundation</name>`, `<description>Compile-time shared foundation the domains build against: domain annotations, the world-gateway seam, the fluent pipeline grammar, and the jGiven wrap.</description>`, and:

```xml
  <modules>
    <module>domain-annotations</module>
    <module>gateway</module>
    <module>pipeline</module>
    <module>jgiven</module>
  </modules>
```

- [ ] **Step 2: git mv the four modules**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
for d in domain-annotations gateway pipeline jgiven; do
  git mv "osgi/$d" "osgi/foundation/$d"
done
```

- [ ] **Step 3: Fix relativePaths (+1 `../`) in every moved pom**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
find osgi/foundation -name pom.xml -not -path "*/target/*" -print0 | while IFS= read -r -d '' p; do
  perl -0pi -e 's{<relativePath>\.\./build-parent/pom\.xml</relativePath>}{<relativePath>../../build-parent/pom.xml</relativePath>}g;
                 s{<relativePath>\.\./\.\./build-parent/pom\.xml</relativePath>}{<relativePath>../../../build-parent/pom.xml</relativePath>}g;
                 s{<relativePath>\.\./bundle-parent/pom\.xml</relativePath>}{<relativePath>../../bundle-parent/pom.xml</relativePath>}g;
                 s{<relativePath>\.\./\.\./bundle-parent/pom\.xml</relativePath>}{<relativePath>../../../bundle-parent/pom.xml</relativePath>}g;
                 s{<relativePath>\.\./\.\./bundle-test-parent/pom\.xml</relativePath>}{<relativePath>../../../bundle-test-parent/pom.xml</relativePath>}g' "$p"
done
```

⚠️ This combined substitution is order-sensitive (the 1-`../` and 2-`../` patterns must not chain). Because perl applies all `s///g` in sequence on the same file, run the deeper-path substitutions are written first would double-apply. SAFER: do it per-depth. Read each moved pom, confirm its current relativePath, and apply ONLY the one matching rule. The known starting values:
- `foundation/domain-annotations/pom.xml`: `../bundle-parent` → `../../bundle-parent`
- `foundation/pipeline/pom.xml`: `../bundle-parent` → `../../bundle-parent`
- `foundation/gateway/pom.xml`: `../../build-parent` → `../../../build-parent`
- `foundation/gateway/gateway-port/pom.xml`: `../../bundle-parent` → `../../../bundle-parent`
- `foundation/jgiven/pom.xml`: `../../build-parent` → `../../../build-parent`
- `foundation/jgiven/jgiven-wrap/pom.xml`: `../../bundle-parent` → `../../../bundle-parent`
- `foundation/jgiven/jgiven-probe/pom.xml`, `jgiven-probe-test/pom.xml`: confirm (likely `../../bundle-parent` or `../../bundle-test-parent`) → +1
- `foundation/jgiven/jgiven-testkit/pom.xml`: `../../../build-parent` → `../../../../build-parent`

Verify each with: `grep -r relativePath osgi/foundation --include=pom.xml`.

- [ ] **Step 4: Update the root `osgi/pom.xml` `<modules>`**

Remove `domain-annotations`, `gateway`, `pipeline`, `jgiven`; add `<module>foundation</module>`.

- [ ] **Step 5: Run the full reactor build — expect green**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | grep -E "BUILD (SUCCESS|FAILURE)|staging-law|Non-resolvable|Could not find|Tests run:" | tail -30
```

Expected: `BUILD SUCCESS`, staging `0 ERROR`. Fix any relativePath miss before committing.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(osgi): group domain-annotations, gateway, pipeline, jgiven under foundation/

Pure relocation; jgiven moved AS-IS (dissolution into pipeline deferred — it is a
realm change, not a layout move: pipeline is type=seam/flat while jGiven installs
as a bundle). artifactIds unchanged. relativePaths +1 ../ per level.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Create the `runtime/` group and move boot, runtime, junit-testkit, bench

**Files:**
- Create: `osgi/runtime/pom.xml` is a NAME COLLISION — see Step 1 (the group is `runtime/`, the existing leaf is also `runtime`). Resolve by moving the leaf into the group FIRST under its eventual name folder, then writing the group pom. (The artifactId rename `runtime`→`runtime-host` is Task 4; here we only relocate, so the leaf moves to `osgi/runtime/runtime/` transiently.)
- Move (git mv): `osgi/boot/` → `osgi/runtime/boot/`; `osgi/runtime/` (leaf) → `osgi/runtime/runtime/`; `osgi/junit-testkit/` → `osgi/runtime/junit-testkit/`; `osgi/bench/` → `osgi/runtime/bench/`
- Modify: moved poms' relativePaths; `osgi/pom.xml` `<modules>`

**Interfaces:**
- Consumes: Tasks 1–2 root `<modules>` shape.
- Produces: `osgi/runtime/pom.xml` (group) listing `boot`, `runtime`, `junit-testkit`, `bench`. The inner leaf keeps artifactId `runtime` until Task 4.

[NOTE] The collision `osgi/runtime/` (leaf) vs `osgi/runtime/` (group) is why the move order matters: you cannot `git mv osgi/boot osgi/runtime/boot` while `osgi/runtime` is still a leaf pom. Sequence below moves the leaf into a temp-named subdir first.

- [ ] **Step 1: Move the existing `runtime` leaf down into a `runtime/runtime/` subdir**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
git mv osgi/runtime osgi/runtime-leaf-tmp        # break the name collision
mkdir -p osgi/runtime
git mv osgi/runtime-leaf-tmp osgi/runtime/runtime
```

(If `git mv osgi/runtime osgi/runtime/runtime` works directly on your git version, use it; the temp-rename is the portable form.)

- [ ] **Step 2: git mv boot, junit-testkit, bench into the group**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
git mv osgi/boot osgi/runtime/boot
git mv osgi/junit-testkit osgi/runtime/junit-testkit
git mv osgi/bench osgi/runtime/bench
```

- [ ] **Step 3: Create the `runtime/` group pom**

Create `osgi/runtime/pom.xml` with `<artifactId>runtime-group</artifactId>` — NO: the aggregator artifactId must be unique but is never referenced as a dependency. Use `<artifactId>runtime</artifactId>` for the GROUP and rely on the leaf being renamed to `runtime-host` in Task 4 to clear the artifactId clash. BUT both poms briefly share artifactId `runtime` (group + leaf) → Maven rejects duplicate artifactId in the reactor.

To keep Task 3 independently green, give the GROUP a distinct artifactId now and the leaf its final name now is out of scope (rename = Task 4). Resolution: name the group `runtime` and TEMPORARILY rename the leaf's artifactId to `runtime-host` as part of THIS task's pom edit (the directory is already `runtime/runtime`; Task 4 then only updates consumers + selector). 

Decision (keep tasks atomic): **fold the leaf artifactId rename into Task 3's pom creation** — the leaf at `osgi/runtime/runtime/pom.xml` gets `<artifactId>runtime-host</artifactId>` + `<name>osgi/runtime/runtime-host</name>` here; the GROUP `osgi/runtime/pom.xml` gets `<artifactId>runtime</artifactId>`, `<name>osgi/runtime</name>`. Then Task 4 updates the 3 exec consumers + selector to match. This avoids a duplicate-artifactId reactor state. Update Task 3's title understanding accordingly.

Group pom:

```xml
  <artifactId>runtime</artifactId>
  <packaging>pom</packaging>
  <name>osgi/runtime</name>
  <description>What boots and exercises the OSGi framework: the boot discovery/logging, the Felix runtime host, the JUnit-in-OSGi testkit, and the bench proof.</description>
  <modules>
    <module>boot</module>
    <module>runtime</module>
    <module>junit-testkit</module>
    <module>bench</module>
  </modules>
```

(The `<module>runtime</module>` entry is the directory name `osgi/runtime/runtime/`, whose pom now has artifactId `runtime-host`.)

- [ ] **Step 4: Rename the leaf artifactId and fix all relativePaths**

In `osgi/runtime/runtime/pom.xml`: `<artifactId>runtime</artifactId>` → `<artifactId>runtime-host</artifactId>`, `<name>osgi/runtime</name>` → `<name>osgi/runtime/runtime-host</name>`, relativePath `../../build-parent` → `../../../build-parent`.

Fix the other moved poms (+1 `../`), per-depth (confirm each by grep first):
- `osgi/runtime/boot/pom.xml`: `../../build-parent` → `../../../build-parent`
- `osgi/runtime/boot/boot-discovery/pom.xml`, `boot-logging/pom.xml`: `../../bundle-parent` → `../../../bundle-parent`
- `osgi/runtime/junit-testkit/pom.xml`: `../bundle-parent`(or `../../build-parent`) → +1
- `osgi/runtime/bench/pom.xml`: `../../build-parent` → `../../../build-parent`
- `osgi/runtime/bench/bench-*/pom.xml` (7 leaves): `../../bundle-parent`/`../../bundle-test-parent` → `../../../…`

```bash
grep -rn "relativePath" osgi/runtime --include=pom.xml   # confirm all corrected, no stale ../.. on a 3-deep pom
```

- [ ] **Step 5: Update the root `osgi/pom.xml` `<modules>`**

Remove `boot`, `runtime`, `junit-testkit`, `bench`; add `<module>runtime</module>`. Root `<modules>` now reads exactly: `bundle-parent, bundle-test-parent, foundation, runtime, domains`.

- [ ] **Step 6: Run the full reactor build — expect green**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | grep -E "BUILD (SUCCESS|FAILURE)|staging-law|Non-resolvable|Could not find|Duplicate|Tests run:" | tail -30
```

Expected: `BUILD SUCCESS`, `0 ERROR`, no `Duplicate` artifactId warning. The `runtime-host` artifactId now exists but its 3 exec consumers still say `<artifactId>runtime</artifactId>` → they will fail to resolve. So this build will FAIL on the exec modules until Task 4. **Therefore: run the build with `-pl :foundation,:runtime,:domains -am` scoped to osgi only for THIS task's gate** (the osgi reactor must be green; exec consumers are fixed in Task 4):

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true -pl osgi -amd 2>&1 | grep -E "BUILD|staging-law|Tests run:" | tail -20
```

⚠️ This is the ONE task whose gate is the osgi sub-reactor, not the full reactor — because renaming the leaf artifactId (folded in here to avoid a duplicate-artifactId state) breaks the exec consumers until Task 4 repairs them. Task 4 restores the full-reactor-green invariant.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(osgi): group boot/runtime/junit-testkit/bench under runtime/, rename leaf to runtime-host

Relocation + the runtime→runtime-host leaf artifactId rename (folded here to avoid
a duplicate-artifactId reactor state with the new runtime/ group). The 3 exec
consumers (seed-master, manifests-cli, netplan-cli) are repaired in the next task;
the osgi sub-reactor is green now.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Repair the `runtime-host` consumers and the CLI selector

**Files:**
- Modify: `exec/seed-master/pom.xml`, `exec/manifests-cli/pom.xml`, `exec/netplan-cli/pom.xml` (the `<dependency><artifactId>runtime</artifactId>` → `runtime-host`)

**Interfaces:**
- Consumes: Task 3's renamed leaf (artifactId `runtime-host`).
- Produces: full reactor green again (the invariant Task 3 temporarily broke).

- [ ] **Step 1: Update the 3 exec dependency declarations**

In each of `exec/seed-master/pom.xml`, `exec/manifests-cli/pom.xml`, `exec/netplan-cli/pom.xml`, change the dependency `<artifactId>runtime</artifactId>` to `<artifactId>runtime-host</artifactId>`. (groupId/version unchanged; resolution is by reactor.)

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
for f in exec/seed-master/pom.xml exec/manifests-cli/pom.xml exec/netplan-cli/pom.xml; do
  perl -0pi -e 's{(<dependency>\s*<groupId>io\.nxmatic\.rke2lab</groupId>\s*<artifactId>)runtime(</artifactId>)}{${1}runtime-host${2}}g' "$f"
done
grep -rn "runtime-host\|<artifactId>runtime<" exec/*/pom.xml   # confirm: 3 runtime-host, 0 bare runtime
```

⚠️ Confirm the dependency block shape first (read one): if `<groupId>` is on a different line or omitted, adjust the regex. Safer fallback: edit each by hand via the Edit tool.

- [ ] **Step 2: Full reactor build — expect green (invariant restored)**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | grep -E "BUILD (SUCCESS|FAILURE)|staging-law|Could not find|Tests run:" | tail -30
```

Expected: `BUILD SUCCESS`, `0 ERROR`. No `Could not find artifact io.nxmatic.rke2lab:runtime`.

- [ ] **Step 3: Verify the CLI selector note (no code change, doc only if a script hardcodes it)**

```bash
grep -rn "pl :runtime\b" --include="*.sh" --include="*.adoc" --include="*.md" . | grep -v /target/ | grep -v "wip/plans"
```

If any build script or guide hardcodes `-pl :runtime`, update it to `-pl :runtime-host`. (Plans under `wip/plans/` are historical — do NOT edit them.)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(osgi): point exec consumers at runtime-host

The 3 exec apps (seed-master, manifests-cli, netplan-cli) now depend on the
renamed runtime-host artifactId. Full reactor green again.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Rename `gateway`→`world-gateway` (module + package + WorldGatewayCatalog)

**Files:**
- Move (git mv): `osgi/foundation/gateway/gateway-port/` → `osgi/foundation/world-gateway/`; delete the now-empty `osgi/foundation/gateway/` aggregator (singleton reduction, §5.7)
- Rename package dir: `…/world-gateway/src/main/java/io/nxmatic/rke2lab/gateway/port/` → `…/world/gateway/port/` (and the `src/test/java` mirror)
- Modify: the 8 seam types' `package` + `GatewayCatalog`→`WorldGatewayCatalog`; `world-gateway/pom.xml` (artifactId, name, relativePath); `world-gateway/bnd.bnd` (Export-Package); the 30 `io.nxmatic.rke2lab.gateway` importers; the 5 pom consumers of `gateway-port`; `osgi/foundation/pom.xml` `<modules>`

**Interfaces:**
- Consumes: Task 2's `foundation/gateway/gateway-port`.
- Produces: `foundation/world-gateway` bundle (artifactId `world-gateway`, package `io.nxmatic.rke2lab.world.gateway.port`, `type=seam` unchanged). `GatewayCatalog` renamed `WorldGatewayCatalog`. All 30 importers + 5 pom consumers updated.

[NOTE] This is the largest rename. The module name drops `-port` (§5.5: singleton, no `-core` sibling to contrast) but the PACKAGE keeps the `…port` leaf (`io.nxmatic.rke2lab.world.gateway.port`) — module names the concept, package names the API role.

- [ ] **Step 1: git mv the bundle up a level and delete the empty aggregator**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
git mv osgi/foundation/gateway/gateway-port osgi/foundation/world-gateway
git rm osgi/foundation/gateway/pom.xml        # the singleton aggregator is gone
rmdir osgi/foundation/gateway 2>/dev/null || true
```

- [ ] **Step 2: Rename the package directories (main + test)**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge/osgi/foundation/world-gateway
for root in src/main/java src/test/java; do
  if [ -d "$root/io/nxmatic/rke2lab/gateway/port" ]; then
    mkdir -p "$root/io/nxmatic/rke2lab/world/gateway"
    git mv "$root/io/nxmatic/rke2lab/gateway/port" "$root/io/nxmatic/rke2lab/world/gateway/port"
    git rm -r --ignore-unmatch "$root/io/nxmatic/rke2lab/gateway" 2>/dev/null || rmdir "$root/io/nxmatic/rke2lab/gateway" 2>/dev/null || true
  fi
done
```

- [ ] **Step 3: Rewrite the package declaration + GatewayCatalog rename across the repo**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
# package rename in every java file (declarations AND imports), repo-wide:
grep -rln "io\.nxmatic\.rke2lab\.gateway\.port" --include="*.java" . | grep -v /target/ | while read -r f; do
  perl -0pi -e 's{io\.nxmatic\.rke2lab\.gateway\.port}{io.nxmatic.rke2lab.world.gateway.port}g' "$f"
done
# GatewayCatalog → WorldGatewayCatalog (type name), repo-wide:
grep -rln "GatewayCatalog" --include="*.java" . | grep -v /target/ | while read -r f; do
  perl -0pi -e 's{\bGatewayCatalog\b}{WorldGatewayCatalog}g' "$f"
done
# rename the file itself:
git mv osgi/foundation/world-gateway/src/main/java/io/nxmatic/rke2lab/world/gateway/port/GatewayCatalog.java \
       osgi/foundation/world-gateway/src/main/java/io/nxmatic/rke2lab/world/gateway/port/WorldGatewayCatalog.java
```

- [ ] **Step 4: Update the bundle's bnd Export-Package**

In `osgi/foundation/world-gateway/bnd.bnd`, change any `Export-Package: io.nxmatic.rke2lab.gateway.port` → `io.nxmatic.rke2lab.world.gateway.port` (and the Bundle-SymbolicName if it embeds the old name — read it first; keep `Provide-Capability: io.nxmatic.rke2lab.embed; type=seam` unchanged).

```bash
perl -0pi -e 's{io\.nxmatic\.rke2lab\.gateway\.port}{io.nxmatic.rke2lab.world.gateway.port}g;
               s{io\.nxmatic\.rke2lab\.gateway}{io.nxmatic.rke2lab.world.gateway}g' osgi/foundation/world-gateway/bnd.bnd
```

- [ ] **Step 5: Update the world-gateway pom (artifactId, name, relativePath, internal package refs in systemPackages if any)**

In `osgi/foundation/world-gateway/pom.xml`: `<artifactId>gateway-port</artifactId>` → `<artifactId>world-gateway</artifactId>`, `<name>osgi/gateway/gateway-port</name>` → `<name>osgi/foundation/world-gateway</name>`. relativePath: it WAS `osgi/foundation/gateway/gateway-port` (3 deep) → now `osgi/foundation/world-gateway` (2 deep), so relativePath to bundle-parent goes from `../../../bundle-parent` (set in Task 2) back to `../../bundle-parent`.

- [ ] **Step 6: Update the 5 pom consumers of `gateway-port`**

`osgi/domains/doctor/doctor-core/pom.xml`, `osgi/domains/doctor/doctor-core-test/pom.xml`, `osgi/domains/doctor/doctor-port/pom.xml`, `exec/seed-master/pom.xml` — change `<artifactId>gateway-port</artifactId>` → `<artifactId>world-gateway</artifactId>`.

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -rln "<artifactId>gateway-port</artifactId>" --include=pom.xml . | grep -v /target/ | while read -r f; do
  perl -0pi -e 's{<artifactId>gateway-port</artifactId>}{<artifactId>world-gateway</artifactId>}g' "$f"
done
grep -rn "<artifactId>gateway-port<" --include=pom.xml . | grep -v /target/   # expect: empty
```

- [ ] **Step 7: Update `osgi/foundation/pom.xml` `<modules>`**

`<module>gateway</module>` → `<module>world-gateway</module>`.

- [ ] **Step 8: Sweep for any residual `gateway` reference**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
echo "--- java/bnd/pom residue (expect empty) ---"
grep -rn "rke2lab\.gateway\|gateway-port\|GatewayCatalog" --include="*.java" --include="*.bnd" --include=pom.xml . | grep -v /target/ | grep -v "wip/plans" | grep -v "docs/"
```

Expected: empty. (Hits under `docs/` are spec prose — the spec/2C doc deliberately keep "gateway" as historical narration; leave them. Hits under `wip/plans/` are historical.)

- [ ] **Step 9: Full reactor build — expect green**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | grep -E "BUILD (SUCCESS|FAILURE)|staging-law|Could not find|cannot find symbol|package .* does not exist|Tests run:" | tail -40
```

Expected: `BUILD SUCCESS`, `0 ERROR`, doctor in-container tests green (`DoctorCoreInContainerTest`, `DoctorPortInContainerTest` exercise the seam — they prove the package rename resolved in-container, not just flat).

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(osgi): rename gateway→world-gateway (module + package + WorldGatewayCatalog)

The single inter-world door, named for the role that survives embedded→remote RSA.
The gateway/ singleton aggregator is reduced (§5.7): the bundle rises to
foundation/world-gateway, the -port suffix drops from the MODULE name while the
PACKAGE keeps its …port role leaf (io.nxmatic.rke2lab.world.gateway.port). bundle
stays type=seam. 30 importers + 5 pom consumers + the selector updated.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Final layout verification + docs/memory reconciliation

**Files:**
- Modify (if needed): `docs/architecture/osgi/osgi-aggregator-layout-spec.adoc` (§8 contradiction note), the layout memory
- Verify only: the whole tree

**Interfaces:**
- Consumes: Tasks 1–5 (the full re-layout landed).
- Produces: a recorded statement of what shipped vs what deferred (jgiven dissolution).

- [ ] **Step 1: Assert the target root `<modules>` shape**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
sed -n '/<modules>/,/<\/modules>/p' osgi/pom.xml
```

Expected exactly: `bundle-parent`, `bundle-test-parent`, `foundation`, `runtime`, `domains` (5 entries). If `jgiven` or any leaf is still flat, a relocation task was incomplete.

- [ ] **Step 2: Assert the file tree matches §3**

```bash
find osgi -name pom.xml -not -path "*/target/*" | sort
```

Expected: `foundation/{domain-annotations,world-gateway,pipeline,jgiven/*}`, `runtime/{boot/*,runtime/* (artifactId runtime-host),junit-testkit,bench/*}`, `domains/{doctor/*,cluster/*,systemd/*,manifests/*,netplan/*,unitrepo/*,ssh-to-age-edge}`. NO `osgi/gateway`, NO flat domains.

- [ ] **Step 3: Final full reactor build with the staging gate inspected**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | tee /tmp/layout-verify.log | grep -E "BUILD (SUCCESS|FAILURE)|staging-law|realm-boundary|Tests run:" | tail -30
```

Expected: `BUILD SUCCESS`; the staging WARN summary unchanged from pre-layout (same gate counts — the layout moved no code, so `realm-boundary` is still ~38 WARN, `0 ERROR`). A CHANGED gate count means the layout accidentally altered a bundle's classpath — investigate before declaring done.

- [ ] **Step 4: Record the §5.4 deferral in the spec and memory**

Add a `[NOTE]` to spec §5.4 stating the dissolution is deferred to its own increment (realm change, not layout) and that §8's "no fusion" governs this increment. Update `.claude/memory/osgi-aggregator-layout-spec-state.md` to mark the layout SHIPPED with the jgiven dissolution explicitly deferred. (Memory edits are not committed code; the spec edit is.)

- [ ] **Step 5: Commit the doc reconciliation**

```bash
git add docs/architecture/osgi/osgi-aggregator-layout-spec.adoc
git commit -m "docs(osgi): mark layout shipped; jgiven dissolution deferred to its own increment

The §5.4 jgiven→pipeline dissolution is a realm change (pipeline is type=seam/flat,
jGiven installs as a bundle), not a layout move — §8's 'no fusion' governs this
increment. The 3 groups + runtime-host + world-gateway shipped; jgiven relocated as-is.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- §3 target layout (3 groups + 2 parents flat) → Tasks 1–3 + Task 6 Step 1–2. ✓
- §5.1 ssh-to-age-edge flat leaf under domains/ → Task 1. ✓
- §5.2 runtime→runtime-host → Tasks 3–4. ✓
- §5.3 parents stay flat → never moved (Tasks touch only the 15 non-parent modules). ✓
- §5.4 jgiven dissolution → DEFERRED (Global Constraints + Task 2 + Task 6 Step 4), with rationale. ✓ (intentional gap, documented)
- §5.5 gateway→world-gateway (module + package + WorldGatewayCatalog) → Task 5. ✓
- §5.6 testing/ erased (junit-testkit + bench → runtime/) → Task 3. ✓
- §5.7 aggregator iff ≥2 (gateway singleton reduced; ssh-to-age-edge flat) → Tasks 1, 5. ✓
- §6 migration mechanics (git mv, relativePath +1, <modules>, build-verify) → every task. ✓

**2. Placeholder scan:** No "TBD"/"handle errors"/"similar to". The one judgment point (Task 3's collision resolution) is spelled out with the chosen resolution (fold the leaf artifactId rename into Task 3). ✓

**3. Type/path consistency:** relativePath depths cross-checked against the verified current values (Task 1–3 list each known starting value). artifactId `runtime`→`runtime-host` consumed by Task 4 exactly as Task 3 produces it. Package `io.nxmatic.rke2lab.world.gateway.port` + `WorldGatewayCatalog` used consistently in Task 5. ✓

**Known intentional deviation from spec:** jgiven dissolution (§5.4) deferred — flagged in Global Constraints, the spec is contradictory (§5.4 vs §8), and the dissolution is realm-sensitive. This is a controller/human decision already taken (user, 2026-06-30), not an oversight.
