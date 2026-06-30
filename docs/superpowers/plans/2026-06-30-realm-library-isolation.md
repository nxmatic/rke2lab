# Realm Library Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each world load its own copy of jackson — host flat (JCL), OSGi as an installed bundle — by ending the `system.packages.extra` share, and re-derive `DUPLICATE_REALM_CLASS`'s flat∧staged exemption from the `type=seam` package surface.

**Architecture:** Four mechanism deltas in the build-time staging extension + the runtime boot planner, plus one gate-law change, all derived from what bundles declare (no hand-list). jackson's poms are untouched (already compile-scope everywhere). The OSGi framework resolves doctor-core's jackson import bundle-to-bundle once the mirror stops short-circuiting it.

**Tech Stack:** Java 25 (JDK 25 via flox), Maven multi-module (reactor-only, `-am`), ASM 9.8 (gate bytecode introspection), bnd (OSGi manifests), Felix (embedded framework), JUnit 5.

## Global Constraints

- Maven runs through flox ONLY: `flox activate -- ./mvnw …`. NEVER `mvn install` to `~/.m2` — inter-module deps resolve through the reactor from each module's `target/`; a module build uses `-am`.
- Tests are skipped by default (root `.mvn` config). To execute them pass `-DskipTests=false`. A `BUILD SUCCESS` with no `Tests run:` line means they were skipped.
- The whole-increment gate is the full reactor verify: `flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true`. NEVER a flat `-Dtest=` as the gate — two-realm collisions only surface in-container.
- In-place text edits use `perl -0pi -e` (GNU sed rejects BSD `-i ''`).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Work directly on `feature/cluster-edge` (no dedicated worktree — standing session decision).
- The two StagingGate enum copies (annotation module + ASM mirror) are NOT touched by this increment — `DUPLICATE_REALM_CLASS` already exists in both. Only the gate's *check logic* changes.
- `REALM_BOUNDARY` stays at `ERROR` throughout (this increment moves no type across a realm). `DUPLICATE_REALM_CLASS` stays at `ERROR`.
- Spec of record: `docs/architecture/osgi/realm-library-isolation-spec.adoc` (commit 3aaa66fb).

## Vocabulary (from the spec, exact)

- **realm library**: a third-party OSGi bundle (carries `Bundle-SymbolicName`, NOT under `io.nxmatic.rke2lab.*`, NOT the framework launcher, NOT a `type=seam` carrier) whose exported package is imported by a `model`/`edge`/`record` (i.e. `EmbedCapability.isDomain()`) bundle. Today: jackson-core, jackson-databind, jackson-annotations, jackson-dataformat-yaml, snakeyaml.
- **flat∧staged**: a package present BOTH in the host uber-jar (flat) AND exported by an installed bundle. A realm library is intentionally flat∧staged.
- **seam surface**: the union of all `type=seam` bundles' `Export-Package` names. The derived exemption set for `DUPLICATE_REALM_CLASS`.

## File Structure

- `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/StagingClosure.java` — add realm-library detection: a third-party OSGi bundle exporting a domain-imported package must be staged even though the host also serves it flat. Add `shadeExcludeGas()`.
- `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/StagingExecutionStrategy.java` — `injectShadeExcludes` consumes `closure.shadeExcludeGas()` (staged minus realm libraries) instead of `closure.stagedGas()`.
- `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/DuplicateRealmClass.java` — exemption derived from the seam surface, replacing the `org.slf4j` hand-list.
- `osgi/runtime/boot/boot-discovery/src/main/java/io/nxmatic/rke2lab/osgi/boot/discovery/BootPlanner.java` — `deriveSystemExports` drops from the mirror any package exported by ANY installed bundle (not only domain bundles).
- `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/package-info.java` — remove the now-dead `@GovernedBy(DUPLICATE_REALM_CLASS, WARN)` once cdk8s is proven seam-absent (it becomes exempt by derivation).
- Test files mirror each source file under `src/test/...`.

---

### Task 1: StagingClosure stages a realm library (flat∧staged), keeping it out of `hostFlatPackages` suppression

**Files:**
- Modify: `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/StagingClosure.java`
- Test: `maven-embed-staging-ext/staging-extension/src/test/java/io/nxmatic/rke2lab/maven/staging/StagingClosureTest.java` (create if absent)

**Interfaces:**
- Consumes: `ResolvedBundle` (`isBundle()`, `embed()`, `imports()`, `exports()`, `launcher()`, `ga()`), `EmbedCapability.isDomain()`/`isSeam()`, `ResolvedBundle.isOurs(String)`.
- Produces: `StagingClosure.staged()` now ALSO contains realm-library bundles; new accessor `Set<String> shadeExcludeGas()` (staged minus realm libraries) for Task 2; new accessor `Set<String> realmLibraryGas()` (the realm libraries) for trace/tests.

- [ ] **Step 1: Write the failing test — a third-party OSGi bundle a model imports is staged**

Add to `StagingClosureTest.java` (use the existing `ResolvedBundle` constructor pattern from `DuplicateRealmClassTest` — build via `new ResolvedBundle(g, a, v, file, bsn, embed, imports, exports, launcher)` with `OsgiHeader.parse(...)`):

```java
@Test
void aThirdPartyBundleAModelImportsIsStagedAsRealmLibrary() {
  // doctor-core (model) imports com.fasterxml.jackson.databind; jackson-databind (third-party
  // OSGi bundle) exports it. The realm library MUST be staged (its own OSGi copy) even though the
  // host serves it flat too.
  final ResolvedBundle model =
      bundle("io.nxmatic.rke2lab", "doctor-core", "model",
          /*imports*/ "com.fasterxml.jackson.databind",
          /*exports*/ "io.nxmatic.rke2lab.doctor");
  final ResolvedBundle jackson =
      thirdParty("com.fasterxml.jackson.core", "jackson-databind",
          /*exports*/ "com.fasterxml.jackson.databind");

  final StagingClosure closure = StagingClosure.compute(List.of(model, jackson));

  assertTrue(closure.stagedGas().contains("com.fasterxml.jackson.core:jackson-databind"),
      "the realm library is staged as a bundle");
  assertTrue(closure.realmLibraryGas().contains("com.fasterxml.jackson.core:jackson-databind"),
      "it is classified a realm library (flat AND staged)");
}
```

Add helpers in the test:

```java
private static ResolvedBundle bundle(String g, String a, String type, String imports, String exports) {
  return new ResolvedBundle(g, a, "1", null, g + "." + a,
      EmbedCapability.of(OsgiHeader.parse(
          "io.nxmatic.rke2lab.embed;type=" + type)),
      OsgiHeader.parse(imports), OsgiHeader.parse(exports), false);
}

private static ResolvedBundle thirdParty(String g, String a, String exports) {
  return new ResolvedBundle(g, a, "1", null, g + "." + a, null,
      OsgiHeader.parse(null), OsgiHeader.parse(exports), false);
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `flox activate -- ./mvnw -q -pl :staging-extension -am test -DskipTests=false -Dtest=StagingClosureTest#aThirdPartyBundleAModelImportsIsStagedAsRealmLibrary`
Expected: FAIL — `stagedGas()` does NOT contain jackson (current code suppresses it via `hostFlatPackages`), and `realmLibraryGas()` does not compile yet.

- [ ] **Step 3: Implement realm-library staging in StagingClosure**

In the `Computation` class, add a field and populate it. The rule: a package imported by a domain bundle is host-flat (stays in `hostFlatPackages` so the host keeps its flat copy), BUT if a *third-party OSGi bundle* in the resolved set exports that package, that bundle is a realm library and must be staged. Modify `seed()` to also seed realm libraries:

```java
private final Map<String, ResolvedBundle> realmLibraryByGa = new LinkedHashMap<>();

/** Third-party OSGi bundles exporting a package a domain bundle imports — staged AND kept flat. */
private void seedRealmLibraries() {
  final Set<String> domainImports = new LinkedHashSet<>();
  for (ResolvedBundle b : resolved) {
    if (b.embed() != null && b.embed().isDomain()) {
      domainImports.addAll(b.imports().names());
    }
  }
  for (ResolvedBundle b : resolved) {
    if (isRealmLibrary(b, domainImports)) {
      realmLibraryByGa.put(b.ga(), b);
      stage(b, "seed: realm library (a domain bundle imports its export)");
    }
  }
}

/** A third-party OSGi bundle (not ours, not a seam, not the launcher) exporting a domain import. */
private static boolean isRealmLibrary(ResolvedBundle b, Set<String> domainImports) {
  if (!b.isBundle() || b.launcher()) {
    return false;
  }
  if (b.embed() != null) {
    return false; // ours (model/edge/record/seam) — not a third-party library.
  }
  for (String exported : b.exports().names()) {
    if (!ResolvedBundle.isOurs(exported) && domainImports.contains(exported)) {
      return true;
    }
  }
  return false;
}
```

Call `seedRealmLibraries()` from `run()` AFTER `seed()` (so domain seeds are staged first, then realm libraries, then the closure fans out):

```java
StagingClosure run() {
  seed();
  seedRealmLibraries();
  close();
  return new StagingClosure(new ArrayList<>(stagedByGa.values()), trace, Set.copyOf(realmLibraryByGa.keySet()));
}
```

- [ ] **Step 4: Add `realmLibraryGas()` + `shadeExcludeGas()` to the record**

Change the record signature and add accessors:

```java
public record StagingClosure(List<ResolvedBundle> staged, List<String> trace, Set<String> realmLibraryGas) {

  public Set<String> stagedGas() { /* unchanged */ }

  /** The staged jars to EXCLUDE from the flat uber-jar — staged minus the realm libraries
   *  (a realm library is staged as a bundle AND kept flat in the host). */
  public Set<String> shadeExcludeGas() {
    final Set<String> excludes = new LinkedHashSet<>(stagedGas());
    excludes.removeAll(realmLibraryGas);
    return excludes;
  }
}
```

Update the `new StagingClosure(...)` call in `run()` to pass the third arg (done in Step 3).

- [ ] **Step 5: Run the test, verify it passes**

Run: `flox activate -- ./mvnw -q -pl :staging-extension -am test -DskipTests=false -Dtest=StagingClosureTest`
Expected: PASS.

- [ ] **Step 6: Add a guard test — a seam package is NOT a realm library**

```java
@Test
void aSeamPackageIsNotStagedEvenIfAModelImportsIt() {
  final ResolvedBundle model =
      bundle("io.nxmatic.rke2lab", "doctor-core", "model",
          "io.nxmatic.rke2lab.world.gateway.port", "io.nxmatic.rke2lab.doctor");
  final ResolvedBundle seam =
      bundle("io.nxmatic.rke2lab", "world-gateway", "seam",
          null, "io.nxmatic.rke2lab.world.gateway.port");
  final StagingClosure closure = StagingClosure.compute(List.of(model, seam));
  assertTrue(closure.realmLibraryGas().isEmpty(), "a seam is host-flat, never a realm library");
}
```

Run the same test command; expected PASS (the `b.embed() != null` early-return and `isOurs` filter both exclude it).

- [ ] **Step 7: Commit**

```bash
git add maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/StagingClosure.java \
        maven-embed-staging-ext/staging-extension/src/test/java/io/nxmatic/rke2lab/maven/staging/StagingClosureTest.java
git commit -m "feat(staging): StagingClosure stages a realm library flat-and-staged"
```

---

### Task 2: `injectShadeExcludes` keeps realm libraries in the shade

**Files:**
- Modify: `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/StagingExecutionStrategy.java:495-510`

**Interfaces:**
- Consumes: `StagingClosure.shadeExcludeGas()` (Task 1).
- Produces: shade excludes that omit realm libraries — jackson stays flat in the uber-jar while also staged.

- [ ] **Step 1: Change the iteration source**

In `injectShadeExcludes` (line ~501), change:

```java
for (String ga : closure.stagedGas()) {
```
to:
```java
for (String ga : closure.shadeExcludeGas()) {
```

This is the ONLY change — `staging-extension` has no unit test harness for the Mojo (it drives Maven internals); the behavior is proven by the integration gate (Task 6) where the shade must retain jackson while staging it. The `injectStagingArtifactItems` method (which stages the bundles) still reads `closure.staged()`, so realm libraries ARE staged — only the shade-exclude list shrinks.

- [ ] **Step 2: Verify the module still compiles**

Run: `flox activate -- ./mvnw -q -pl :staging-extension -am test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/StagingExecutionStrategy.java
git commit -m "feat(staging): keep realm libraries in the shade (flat AND staged)"
```

---

### Task 3: BootPlanner stops mirroring any installed-bundle export

**Files:**
- Modify: `osgi/runtime/boot/boot-discovery/src/main/java/io/nxmatic/rke2lab/osgi/boot/discovery/BootPlanner.java:140-198`
- Test: `osgi/runtime/boot/boot-discovery/src/test/java/io/nxmatic/rke2lab/osgi/boot/discovery/BootPlannerTest.java`

**Interfaces:**
- Consumes: `BundleIndex` (the index of installable bundles), each model's `imports().asSystemExports()`.
- Produces: `system.packages.extra` that EXCLUDES a package exported by any installed bundle (realm library or domain), so doctor-core's jackson import wires bundle-to-bundle.

- [ ] **Step 1: Write the failing test — a staged realm library is NOT mirrored**

Add to `BootPlannerTest.java` (stages jackson as an installed bundle alongside the model):

```java
@Test
void aStagedRealmLibraryIsNotMirroredIntoSystemExports(@TempDir Path dir) throws IOException {
  // doctor-core (model) imports com.fasterxml.jackson.databind; jackson-databind is ALSO staged as
  // a bundle (a realm library). Because an installed bundle now provides the package, it must NOT
  // be system-exported — the bundle is the sole in-framework provider, wired bundle-to-bundle.
  stage(dir, "model.jar", Map.of(
      "Bundle-SymbolicName", "com.example.model",
      "Provide-Capability", EMBED + ";type=model",
      "Export-Package", "com.example.model.api",
      "Import-Package", "com.fasterxml.jackson.databind"));
  stage(dir, "jackson-databind.jar", Map.of(
      "Bundle-SymbolicName", "com.fasterxml.jackson.core.jackson-databind",
      "Export-Package", "com.fasterxml.jackson.databind"));

  final BootPlan plan = planOf(dir, p -> true); // host carries jackson flat too

  assertTrue(
      plan.systemPackagesExtra().stream().noneMatch(e -> e.startsWith("com.fasterxml.jackson")),
      "a package an installed bundle exports is NOT mirrored — it wires bundle-to-bundle");
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `flox activate -- ./mvnw -q -pl :boot-discovery -am test -DskipTests=false -Dtest=BootPlannerTest#aStagedRealmLibraryIsNotMirroredIntoSystemExports`
Expected: FAIL — current `deriveSystemExports` only removes packages exported by the `models` list, not by other installed bundles like jackson, so jackson stays mirrored.

- [ ] **Step 3: Implement — broaden the export-removal to all installed bundles**

In `deriveSystemExports` (BootPlanner.java:147-154), the current code collects `bundleExportedPackages` only from the `models` manifests. Broaden it to every installable bundle in the index. Replace lines 147-154:

```java
    final Set<String> bundleExportedPackages = new LinkedHashSet<>();
    for (BundleManifest manifest : manifests) {
      bundleExportedPackages.addAll(manifest.exports().names());
    }
    for (BundleManifest manifest : manifests) {
      exports.addAll(manifest.imports().asSystemExports());
    }
    exports.removeIf(e -> bundleExportedPackages.contains(Clause.parse(e).name()));
```

with (collect exports from EVERY installable bundle, including staged realm libraries):

```java
    final Set<String> installedExportedPackages = new LinkedHashSet<>();
    for (BundleLocation location : discovery.all()) {
      installedExportedPackages.addAll(discovery.manifestOf(location).exports().names());
    }
    for (BundleManifest manifest : manifests) {
      exports.addAll(manifest.imports().asSystemExports());
    }
    // A package an installed bundle exports is provided bundle-to-bundle inside the framework —
    // re-exporting it from the system bundle would split the class. This covers domain bundles
    // (their own exports) AND staged realm libraries (jackson): the same rule, one source.
    exports.removeIf(e -> installedExportedPackages.contains(Clause.parse(e).name()));
```

Note `discovery.all()` returns the installable set (launcher already excluded — BundleIndex.java:136). `discovery.manifestOf(location)` is public (BundleIndex.java:211).

- [ ] **Step 4: Run the new test + the existing BootPlanner suite**

Run: `flox activate -- ./mvnw -q -pl :boot-discovery -am test -DskipTests=false -Dtest=BootPlannerTest`
Expected: PASS — including the unchanged `mirrorsModelImportsHostFlatIntoSystemExports` (that test stages NO jackson bundle, so jackson has no installed exporter and stays mirrored — the flat-library-without-a-staged-bundle case still holds) and `seamGuardThrows...` (a seam exports the package but the leak guard fires before removal; verify it still throws).

- [ ] **Step 5: Commit**

```bash
git add osgi/runtime/boot/boot-discovery/src/main/java/io/nxmatic/rke2lab/osgi/boot/discovery/BootPlanner.java \
        osgi/runtime/boot/boot-discovery/src/test/java/io/nxmatic/rke2lab/osgi/boot/discovery/BootPlannerTest.java
git commit -m "feat(boot): stop mirroring any installed-bundle export — realm libraries wire bundle-to-bundle"
```

---

### Task 4: `DuplicateRealmClass` derives its exemption from the seam surface

**Files:**
- Modify: `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/DuplicateRealmClass.java`
- Modify: `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/StagingExecutionStrategy.java` (the call site that builds the gate — pass the seam surface)
- Test: `maven-embed-staging-ext/staging-extension/src/test/java/io/nxmatic/rke2lab/maven/staging/DuplicateRealmClassTest.java`

**Interfaces:**
- Consumes: the union of `type=seam` bundles' `exports().names()` (the seam surface), computed in `StagingExecutionStrategy.enforceGates` from `resolved`.
- Produces: a `DuplicateRealmClass(Set<String> flatPackages, Set<String> seamSurface)` whose `violations` exempt any flat∧staged package ABSENT from `seamSurface`.

- [ ] **Step 1: Write the failing test — a flat∧staged package absent from the seam is exempt**

Replace the existing `DuplicateRealmClassTest` body's expectations. New cases:

```java
@Test
void aFlatAndStagedPackageAbsentFromTheSeamIsExempt() {
  // jackson is flat AND staged, but no seam exports it → safe, exempt (the derived rule).
  final DuplicateRealmClass gate = new DuplicateRealmClass(
      Set.of("com.fasterxml.jackson.databind"), /*seamSurface*/ Set.of("io.nxmatic.rke2lab.world.gateway.port"));
  assertTrue(gate.violations(exporting("com.fasterxml.jackson.databind;version=2.22.0")).isEmpty(),
      "flat∧staged is safe when the package is not on any seam");
}

@Test
void aFlatAndStagedPackagePRESENTOnTheSeamIsAViolation() {
  // if a seam exported jackson, a type could cross → the duplication is dangerous → flagged.
  final DuplicateRealmClass gate = new DuplicateRealmClass(
      Set.of("com.fasterxml.jackson.databind"), Set.of("com.fasterxml.jackson.databind"));
  final List<String> v = gate.violations(exporting("com.fasterxml.jackson.databind;version=2.22.0"));
  assertEquals(1, v.size(), "a seam carrying the package loses the exemption");
  assertEquals("com.fasterxml.jackson.databind", v.get(0));
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `flox activate -- ./mvnw -q -pl :staging-extension -am test -DskipTests=false -Dtest=DuplicateRealmClassTest`
Expected: FAIL — `DuplicateRealmClass` has a one-arg constructor and the `ALLOWED_SHARED_ROOTS` hand-list, not a seam-surface arg.

- [ ] **Step 3: Implement the seam-derived exemption**

Replace `DuplicateRealmClass.java` fields + constructor + `isAllowedShared`:

```java
  private final Set<String> flatPackages;
  private final Set<String> seamSurface;

  DuplicateRealmClass(Set<String> flatPackages, Set<String> seamSurface) {
    this.flatPackages = flatPackages;
    this.seamSurface = seamSurface;
  }

  /** The packages a staged bundle exports that ALSO live flat AND appear on a seam — each a
   *  dangerous cross-realm duplication (a type from it can cross the seam). A flat∧staged package
   *  absent from every seam cannot cross, so it is exempt — the derived realm-library case. */
  List<String> violations(ResolvedBundle stagedBundle) {
    final List<String> lines = new ArrayList<>();
    for (String exported : stagedBundle.exports().names()) {
      if (flatPackages.contains(exported) && seamSurface.contains(exported)) {
        lines.add(exported);
      }
    }
    return lines;
  }
```

Delete `ALLOWED_SHARED_ROOTS` and `isAllowedShared` entirely (no-dead-code).

- [ ] **Step 4: Update the call site in StagingExecutionStrategy.enforceGates**

Find where `new DuplicateRealmClass(flatPackages)` is constructed (line ~269). Build the seam surface from `resolved` and pass it:

```java
    final Set<String> seamSurface = new java.util.LinkedHashSet<>();
    for (ResolvedBundle b : resolved) {
      if (b.embed() != null && b.embed().isSeam()) {
        seamSurface.addAll(b.exports().names());
      }
    }
    final DuplicateRealmClass duplicate = new DuplicateRealmClass(flatPackages, seamSurface);
```

- [ ] **Step 5: Run the test + compile the module**

Run: `flox activate -- ./mvnw -q -pl :staging-extension -am test -DskipTests=false -Dtest=DuplicateRealmClassTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/DuplicateRealmClass.java \
        maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/StagingExecutionStrategy.java \
        maven-embed-staging-ext/staging-extension/src/test/java/io/nxmatic/rke2lab/maven/staging/DuplicateRealmClassTest.java
git commit -m "feat(staging): DUPLICATE_REALM_CLASS exemption derived from the seam surface, slf4j hand-list deleted"
```

---

### Task 5: Verify cdk8s + slf4j are seam-absent; remove the dead controlplane WARN

**Context (verified, do not re-litigate):** cdk8s is a GENUINE dual-realm library, not a scope
leftover. The host uses it deliberately at compile scope — `IncusResourceBootstrap` builds
`org.cdk8s.App`/`Chart` (lines 2194-2195) to synthesize the incus host-slot manifests, and
`HostSlotManifest` imports cdk8s types. The OSGi manifests world (manifests-core) uses its own cdk8s
copy to synthesize k8s cluster manifests. Two worlds, two manifest-synthesis jobs, two isolated
copies — cdk8s objects never cross the seam (the host emits its charts internally, OSGi emits its
charts internally; only Strings/Documents cross). So cdk8s is flat∧staged for the SAME legitimate
reason as jackson, and the seam-derived exemption applies because no seam exports `org.cdk8s` /
`software.constructs`. (Backlog, NOT this increment: whether host-side manifest synthesis should
eventually consolidate into the OSGi manifests world — a separate, larger architectural question.)

**Files:**
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/package-info.java`

**Interfaces:**
- Consumes: nothing new — verification + a deletion.
- Produces: the controlplane package-info returns to the locked `DUPLICATE_REALM_CLASS` = `ERROR` default (the WARN pose is dead once cdk8s is exempt by derivation).

- [ ] **Step 1: Prove cdk8s and slf4j are absent from every seam's Export-Package**

Run:
```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -rl 'type=seam' --include=bnd.bnd osgi | while read f; do
  d=$(dirname "$f"); echo "== seam: $d =="; grep -A20 'Export-Package' "$f"
done
```
Expected: NO seam Export-Package lists `org.cdk8s`, `software.constructs`, or `org.slf4j`. (If any does, STOP — the derivation would flag it; escalate.)

- [ ] **Step 2: Remove the dead `@GovernedBy(DUPLICATE_REALM_CLASS, WARN)` from controlplane**

The controlplane package-info currently poses `@GovernedBy(value = StagingGate.DUPLICATE_REALM_CLASS, level = EnforcementLevel.WARN)` for the cdk8s duplication. With the seam-derived exemption, cdk8s (seam-absent) is no longer a violation, so the WARN is dead. Replace the whole package-info with the plain locked-default form:

```java
/**
 * The flat host control-plane. {@code REALM_BOUNDARY} and {@code DUPLICATE_REALM_CLASS} are both
 * build-enforced at their locked ERROR default — the host holds no bundle-only doctor.records type.
 * cdk8s is a legitimate dual-realm library: the host synthesizes its incus host-slot manifests with
 * its own flat cdk8s copy while the OSGi manifests world synthesizes the k8s cluster manifests with
 * its own bundle copy — cdk8s objects never cross the seam, so the seam-purity derivation exempts
 * the flat∧staged duplication (no seam exports org.cdk8s / software.constructs). The world-exchange
 * migration crosses the boundary only as opaque Documents (see
 * docs/architecture/osgi/world-exchange-spec.adoc).
 */
package io.nxmatic.rke2lab.controlplane;
```

Remove the now-unused imports (`EnforcementLevel`, `GovernedBy`, `StagingGate`).

- [ ] **Step 3: Verify the module compiles**

Run: `flox activate -- ./mvnw -q -pl :seed-master -am test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/package-info.java
git commit -m "refactor(controlplane): drop dead DUPLICATE_REALM_CLASS WARN — cdk8s exempt by seam derivation"
```

---

### Task 6: Full-reactor integration gate — jackson isolated, all gates green, in-container passes

**Files:** none (verification task).

**Interfaces:**
- Consumes: all prior tasks.
- Produces: proof the increment holds end-to-end.

- [ ] **Step 1: Run the full reactor verify**

Run: `flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true`
Expected: `BUILD SUCCESS` with `Tests run:` lines present.

- [ ] **Step 2: Confirm the gate summary shows zero DUPLICATE_REALM_CLASS errors AND zero warnings**

In the build log, find the `[osgi-staging] gate summary` line. Expected: `duplicate-realm-class: 0 error, 0 warn` (cdk8s now exempt). `realm-boundary: 0 error, 0 warn`.

- [ ] **Step 3: Confirm jackson is staged AND flat**

Run:
```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
JAR=exec/seed-master/target/seed-master-*.jar
echo "=== jackson staged as a bundle? ==="
unzip -l $JAR | grep 'META-INF/bundles/jackson' || echo "MISSING — jackson not staged"
echo "=== jackson ALSO flat in the uber-jar? ==="
unzip -l $JAR | grep 'com/fasterxml/jackson/databind/ObjectMapper.class' || echo "MISSING — jackson not flat"
```
Expected: BOTH present — `META-INF/bundles/jackson-databind.jar` (staged) AND `com/fasterxml/jackson/databind/ObjectMapper.class` (flat). The flat∧staged shape, intentional.

- [ ] **Step 4: Confirm the doctor in-container tests passed**

In the build log, confirm the seed-master in-container suite (the embedded-framework boot test `EmbeddedBundlesBootTest` and the doctor round-trip tests) report `Tests run: N, Failures: 0, Errors: 0`. A `LinkageError`/`ClassCastException` here would mean a jackson type crossed the seam — it must NOT appear.

- [ ] **Step 5: No commit (verification only). If green, the increment is complete.**

If any step fails, use superpowers:systematic-debugging — do NOT patch symptomatically.

---

## Self-Review

**Spec coverage:**
- "realm library is staged AND kept flat" → Tasks 1 + 2.
- "stop mirroring into system.packages.extra" → Task 3.
- "DUPLICATE_REALM_CLASS exemption derived from seam surface" → Task 4.
- "org.slf4j hand-list deleted, derivation-first" → Task 4 + Task 5 Step 1 (empirical seam-absence check).
- "cdk8s WARN resolves to green" → Task 5.
- "green = full reactor + in-container + flat∧staged jackson" → Task 6.

**Type consistency:** `shadeExcludeGas()`/`realmLibraryGas()`/`stagedGas()` consistent across Tasks 1-2. `DuplicateRealmClass(Set, Set)` consistent across Task 4 source + call site + tests. `discovery.all()`/`discovery.manifestOf()` are real public BundleIndex methods (verified BundleIndex.java:136,211).

**Placeholder scan:** none — every code step shows the actual code.

**Open risk flagged for the executor:** Task 3 broadens export-removal to `discovery.all()`. The existing `mirrorsModelImportsHostFlatIntoSystemExports` test stages NO jackson bundle, so jackson has no installed exporter and stays mirrored — that test must still pass (the flat-library-without-staged-bundle case). If it fails, the broadening is too aggressive — investigate before forcing.
