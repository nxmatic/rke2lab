 # REALM_BOUNDARY Staging Gate — Implementation Plan (Plan 1 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fourth build-time staging gate, `REALM_BOUNDARY`, that detects when a class references a type unreachable in its own classloader realm — the build-time guard of the host↔OSGi boundary — governed `WARN` so it prints the host↔OSGi drift worklist without breaking the build. Plus two vocabulary clean-ups landed first: rename the gate enum `Gate` → `StagingGate`, and rename `production` → `live`.

**Architecture:** The staging extension (`maven-embed-staging-ext/staging-extension`) already runs three ASM-based laws (`RecordPurity`, `SpecCoverage`, `InstanceDiscipline`) over the resolved bundle set in a fail-at-end `enforceGates`. This plan adds a fourth law, `RealmBoundary`, that differs from its twins in two ways: it reads **method bodies** (not just signatures — the drift `Severity.parse()` is an `invokestatic` in a body), and it scans the **current project's own `target/classes`** (the host exec classes that crash live there, not in a dependency jar). Each violation is auto-attributed by the realm it falls in (flat realm = host/seam leak; bundle realm = OSGi-internal leak). The gate is governed `WARN` during the migration that Plan 2 will perform, then flipped to the locked `ERROR` default once clean.

**Tech Stack:** Java 25 (flox), Maven extension (`MojosExecutionStrategy`), `org.ow2.asm:asm` (core only — no asm-commons/tree on the classpath), JUnit5 Jupiter, the `@GovernedBy(StagingGate, EnforcementLevel)` governance annotation in `osgi/domain-annotations`.

## Global Constraints

- Toolchain JDK 25 via flox: every Maven command is `flox activate -- ./mvnw …`. (verbatim from CLAUDE.md)
- Inter-module deps resolve through the reactor; build with `-am` (also-make). NEVER `mvn install` project artifacts to `~/.m2` — a bare `-pl` resolves siblings from stale jars and fails. (verbatim from CLAUDE.md)
- Tests are skipped by default (`.mvn` forces `-DskipTests`); execute with `-DskipTests=false`. A green build with no `Tests run:` line means tests were skipped, not passed. (verbatim from CLAUDE.md)
- Build cache can replay stale results and leave `target/classes` empty; for any load-bearing verification pass `-Dmaven.build.cache.skipCache=true` (SKIP, keeps the staging extension active — NOT `enabled=false`, which disables it). (`maven-build-cache-and-staging-verify`)
- The staging extension is installed BEFORE the reactor builds `domain-annotations`, so it CANNOT link that module's enums; it reads annotations via ASM and maps enum-constant NAMES onto its own mirror enums. Mirror constant names must stay in step with the annotation module. (verbatim from `staging-gates-governance-spec.adoc`)
- Only `org.ow2.asm:asm` (core) is on the staging-extension classpath. No `asm-commons`, no `asm-tree`. Referenced-type collection must use core ASM visitors only.
- The staging gates govern only OUR code: a type under `io.nxmatic.rke2lab.*` (`ResolvedBundle.isOurs` / `ourExportedPackages`). Foreign packages (cdk8s, jackson, JDK) are out of jurisdiction.
- Artifact id = directory name; group id `io.nxmatic.rke2lab`; specs/plans live under `wip/` and `docs/`. Comments document the *why* only; single-developer repo — no compatibility shims, delete the old API in the same change.
- Design-of-record: `docs/architecture/osgi/staging-gates-governance-spec.adoc` (the four gates + governance) and `docs/architecture/osgi/world-exchange-spec.adoc` (the boundary this gate guards, gate-first sequencing).

## File structure

**Task 0 — rename `production` → `live` (host vocabulary):**
- Rename: `exec/seed-master/.../controlplane/bdd/ProductionClusterReadinessProbe.java` → `LiveClusterReadinessProbe.java` (class + ctor).
- Modify: `exec/seed-master/.../controlplane/resources/ResourceCreationPipeline.java` (import + `new` site), `exec/seed-master/.../controlplane/systemd/SeedSystemdAdapterEndpointGate.java` (`production(...)` factory → `live(...)` + call site in `BootstrapPipeline.java:344`), plus prose-only `production`→`live` across the seed-master + osgi files the inventory lists.

**Task 1 — rename enum `Gate` → `StagingGate`:**
- Rename: `osgi/domain-annotations/.../annotations/Gate.java` → `StagingGate.java`.
- Modify (annotation module): `GovernedBy.java`, `Exempt.java`, `package-info.java`.
- Modify (extension mirror): `maven-embed-staging-ext/.../staging/Gate.java` → `StagingGate.java`, plus `GovernanceReader.java`, `InstanceDiscipline.java`, `ResolvedBundle.java`, `StagingExecutionStrategy.java`.
- Modify (10 callers): the 9 `package-info.java` under `osgi/**` that pose `@GovernedBy(Gate.…)` (the 10th, `domain-annotations`' own, is in the annotation-module list above).
- Modify (tests): `GovernanceReaderTest.java`, `InstanceDisciplineTest.java`.

**Task 2 — `RealmBoundary` law (the gate engine):**
- Create: `maven-embed-staging-ext/staging-extension/.../staging/ReferencedTypes.java` (ASM collector of every referenced type, bodies included).
- Create: `maven-embed-staging-ext/staging-extension/.../staging/RealmBoundary.java` (the law).
- Create: `maven-embed-staging-ext/staging-extension/src/test/.../staging/ReferencedTypesTest.java`, `RealmBoundaryTest.java`.

**Task 3 — wire the gate into `enforceGates` + scan the exec's own classes:**
- Modify: `maven-embed-staging-ext/.../staging/StagingExecutionStrategy.java` (forbidden-set computation, the exec `target/classes` carrier, the new `report.record(StagingGate.REALM_BOUNDARY, …)` calls).
- Modify: `maven-embed-staging-ext/.../staging/ResolvedBundle.java` (a `realmBoundary(...)` accessor + a way to read an exploded classes dir, not only a jar).

**Task 4 — governance anchors + POM dependency wiring (WARN):**
- Modify: `exec/seed-master/pom.xml` and `osgi/doctor/doctor-port/pom.xml` (add `domain-annotations` as a direct dependency — neither has it; cluster-port already does).
- Create: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/package-info.java` (`@GovernedBy(StagingGate.REALM_BOUNDARY, WARN)`).
- Modify: `osgi/doctor/doctor-port/.../doctor/port/package-info.java` (currently only `@Version`) and `osgi/cluster/cluster-port/.../cluster/port/package-info.java` (already poses SPEC_COVERAGE=WARN) — add the `REALM_BOUNDARY` WARN pose to each.

---

## Task 0: Rename `production` → `live` (host vocabulary)

**Files:**
- Rename: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/ProductionClusterReadinessProbe.java` → `LiveClusterReadinessProbe.java`
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/resources/ResourceCreationPipeline.java:6,86`
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/systemd/SeedSystemdAdapterEndpointGate.java:37,56`
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/BootstrapPipeline.java:344`
- Modify (prose only): `EnvironmentStage.java:52`, `ClusterReadinessStage.java:90,92`, `SystemdAdapterStage.java:100`, `ConfigEntryGate.java:23`, `ConfigLoader.java:38`, `Rke2labConfig.java:40`, `ClusterBootstrapReadinessVerifier.java:50`, `SystemdAdapterProbe.java:7`, `SystemdAdapterScenario.java:28,60`, `ClusterReadinessProbe.java:9`, and the osgi prose sites (`DiscoveryPolicy.java:11`, `DoctorGraph.java:18`, `UnitResolver.java:22`, `ManifestsDomainRegistry.java:92`, `FloxShellSidecarProfile.java:134`, `FloxDebugPolicy.java:34,43,74`, `ImageState.java:44`, `BootstrapIdentity.java:57`, `IncusIdentityMaterial.java:41`, `NetworkTopology.java:15`)

**Interfaces:**
- Consumes: nothing (first task).
- Produces: class `LiveClusterReadinessProbe` (was `ProductionClusterReadinessProbe`, same constructor signature); static factory `SeedSystemdAdapterEndpointGate.live(SystemdRuntimeStatus)` (was `.production(...)`). Later tasks do not depend on these.

**Why:** "production" in this repo never means an environment tier (there is no prod/staging/dev — only the bare-metal hosts `nikopol`/`bioskop`). It always means "the real run against the hosts, vs the test fake / offline defaults". `live` is the precise word and already matches the existing `liveProbe` field in `SystemdAdapterStage`. Doing it first keeps it out of the migration diffs (Plan 2) that will rewrite these same files.

- [ ] **Step 1: Rename the class file and its symbol**

Rename the file `ProductionClusterReadinessProbe.java` → `LiveClusterReadinessProbe.java`. Inside, rename the class and constructor:

```java
public final class LiveClusterReadinessProbe implements ClusterReadinessProbe {
  // ... fields unchanged ...
  public LiveClusterReadinessProbe(
      // ... same parameters as before ...
      ) {
    // ... same body ...
  }
  // ... rest unchanged; update any "Production" word in javadoc to "Live" ...
}
```

- [ ] **Step 2: Update the construction site in `ResourceCreationPipeline.java`**

Line 6 import and line 86 instantiation:

```java
import io.nxmatic.rke2lab.controlplane.bdd.LiveClusterReadinessProbe;
// ...
            new LiveClusterReadinessProbe(
                // ... same arguments ...
                ),
```

- [ ] **Step 3: Rename the `production` factory → `live` in `SeedSystemdAdapterEndpointGate.java`**

Line 56 factory and line 37 comment:

```java
  // the incus-exec reachability check). live() wires the live ones; tests substitute fakes so
  // ...
  public static SeedSystemdAdapterEndpointGate live(
      // ... same parameters ...
      ) {
    // ... same body ...
  }
```

- [ ] **Step 4: Update the call site in `BootstrapPipeline.java:344`**

```java
          SeedSystemdAdapterEndpointGate.live(state.systemdRuntimeStatus);
```

- [ ] **Step 5: Replace the prose `production`/`Production` → `live`/`Live` in the listed files**

Each is a javadoc/comment occurrence — replace the word `Production`→`Live` and `production`→`live`, preserving the sentence. No identifier or signature changes in these files. Verify none of the remaining `production` words are a different sense before replacing (they are all the "real run vs fake" sense per the inventory).

- [ ] **Step 6: Verify it compiles**

Run: `flox activate -- ./mvnw -pl :seed-master -am compile -Dmaven.build.cache.skipCache=true`
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Verify no stray `production`/`Production` identifier survives (prose may remain only where intended)**

Run: `grep -rn "Production" --include="*.java" exec/seed-master/src 2>/dev/null`
Expected: no `class Production…`, no `.production(`, no `new Production…`. (A residual prose "Production" is a missed Step-5 edit — fix it.)

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(seed): rename production -> live (the real-run-vs-fake sense, not an env tier)"
```

---

## Task 1: Rename enum `Gate` → `StagingGate`

**Files:**
- Rename: `osgi/domain-annotations/src/main/java/io/nxmatic/rke2lab/domain/annotations/Gate.java` → `StagingGate.java`
- Modify: `osgi/domain-annotations/.../annotations/GovernedBy.java`, `Exempt.java`, `package-info.java`
- Rename: `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/Gate.java` → `StagingGate.java`
- Modify: `maven-embed-staging-ext/.../staging/GovernanceReader.java`, `InstanceDiscipline.java`, `ResolvedBundle.java`, `StagingExecutionStrategy.java`
- Modify: the 9 `package-info.java` under `osgi/**` posing `@GovernedBy(Gate.…)` (cluster-core, cluster-port, systemd-port, systemd-core, netplan-core, netplan-port, unitrepo-core, manifests-core, manifests-port)
- Modify (tests): `maven-embed-staging-ext/.../staging/GovernanceReaderTest.java`, `InstanceDisciplineTest.java`

**Interfaces:**
- Consumes: nothing from Task 0.
- Produces: enum `io.nxmatic.rke2lab.domain.annotations.StagingGate { RECORD_PURITY, SPEC_COVERAGE, INSTANCE_DISCIPLINE }` (REALM_BOUNDARY added in Task 2); the extension mirror enum `io.nxmatic.rke2lab.maven.staging.StagingGate` with `static StagingGate fromName(String)`. Tasks 2–4 reference `StagingGate`.

**Why:** "gate" is overloaded — runtime readiness gates (`SeedSystemdAdapterEndpointGate`, `ConfigEntryGate`, `ManifestUpdateGate`) and network gateways (`envoyGateway`) share the word. `StagingGate` names the build-time sense explicitly and aligns with the subsystem name (`maven-embed-staging-ext`, "staging laws"). Renaming now (before adding the new value) means the new value lands on an already-disambiguated enum.

- [ ] **Step 1: Rename the annotation-module enum**

Rename `Gate.java` → `StagingGate.java`. Change the declaration and the javadoc `{@link Gate}` self-references:

```java
package io.nxmatic.rke2lab.domain.annotations;

/**
 * The build-time staging laws the {@code staging-extension} enforces from bundle bytecode — the
 * rule a {@link GovernedBy} names so a package can set its reporting {@link EnforcementLevel} per
 * law.
 * ... (javadoc unchanged except Gate -> StagingGate where it self-links) ...
 */
public enum StagingGate {
  RECORD_PURITY,
  SPEC_COVERAGE,
  INSTANCE_DISCIPLINE
}
```

- [ ] **Step 2: Update `GovernedBy.java` and `Exempt.java` (annotation `value()` type)**

In `GovernedBy.java`:

```java
  /** The staging gate this declaration sets the level for. */
  StagingGate value();
```

In `Exempt.java`:

```java
  /** The gate this element is exempt from. */
  StagingGate value();
```

Also update the `{@link Gate…}` javadoc references in both files to `{@link StagingGate…}`.

- [ ] **Step 3: Update `domain-annotations/package-info.java`**

```java
@GovernedBy(value = StagingGate.SPEC_COVERAGE, level = EnforcementLevel.IGNORE)
package io.nxmatic.rke2lab.domain.annotations;
```

Update the javadoc `{@link Gate}` → `{@link StagingGate}`.

- [ ] **Step 4: Rename the extension's mirror enum**

Rename `maven-embed-staging-ext/.../staging/Gate.java` → `StagingGate.java`:

```java
package io.nxmatic.rke2lab.maven.staging;

/**
 * The staging extension's own view of {@code io.nxmatic.rke2lab.domain.annotations.StagingGate} —
 * the build-time staging laws, each governable to its own {@link EnforcementLevel} per package. Read
 * from the {@code @GovernedBy} annotation's enum {@code value} via ASM (the extension cannot link
 * the annotation module; see {@link EnforcementLevel}). The constant NAMES must stay in step with
 * the annotation module's enum.
 */
enum StagingGate {
  RECORD_PURITY,
  SPEC_COVERAGE,
  INSTANCE_DISCIPLINE;

  /** Map an ASM enum-constant name to a gate, or {@code null} for an unknown name (ignored). */
  static StagingGate fromName(String name) {
    for (StagingGate gate : values()) {
      if (gate.name().equals(name)) {
        return gate;
      }
    }
    return null;
  }
}
```

- [ ] **Step 5: Update every `Gate` reference in the extension main + tests**

Replace `Gate` → `StagingGate` in `GovernanceReader.java` (the `Map<Gate,…>`, `Gate.fromName`, `Gate gate`), `InstanceDiscipline.java` (the `INSTANCE_DISCIPLINE` constant comment + any `Gate` ref), `ResolvedBundle.java` (`Map<Gate, EnforcementLevel>` javadoc/return types), `StagingExecutionStrategy.java` (`Map<Gate, EnforcementLevel>`, the `for (Gate gate : Gate.values())`, the `report.record(Gate.…)` calls, the `EnumMap<>(Gate.class)`).

In the two tests:
- `GovernanceReaderTest.java` — replace the enum refs `Gate.SPEC_COVERAGE` / `Gate.RECORD_PURITY` / `Gate.INSTANCE_DISCIPLINE` → `StagingGate.…`, AND the ASM descriptor string constant `private static final String GATE = "io/nxmatic/rke2lab/domain/annotations/Gate";` → `".../StagingGate";` (this string is the bytecode descriptor the synthetic `package-info` writes — it MUST match the renamed annotation-module enum or `GovernanceReader` will not recognise the pose).
- `InstanceDisciplineTest.java` — it references the enum only through the same ASM descriptor string `private static final String GATE = "io/nxmatic/rke2lab/domain/annotations/Gate";` (line 36); rename it to `".../StagingGate"`. (It writes `@Exempt(value = thatGate)` via this descriptor; the rename must match `Exempt.value()`'s new `StagingGate` type.)

- [ ] **Step 6: Update the 9 osgi `package-info.java` callers**

In each of the 9 files, change `Gate.` → `StagingGate.` and the import `import io.nxmatic.rke2lab.domain.annotations.Gate;` → `import io.nxmatic.rke2lab.domain.annotations.StagingGate;`. Example (`cluster-port`):

```java
@GovernedBy(value = StagingGate.SPEC_COVERAGE, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.cluster.port;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
```

- [ ] **Step 7: Verify the extension module compiles and its tests pass**

Run: `flox activate -- ./mvnw -pl :staging-extension -am test -DskipTests=false -Dmaven.build.cache.skipCache=true`
Expected: `BUILD SUCCESS`, `Tests run:` line present, 0 failures (GovernanceReaderTest + InstanceDisciplineTest green against `StagingGate`).

- [ ] **Step 8: Verify the annotation module + a governed osgi module compile**

Run: `flox activate -- ./mvnw -pl :domain-annotations,:cluster-port -am compile -Dmaven.build.cache.skipCache=true`
Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Verify no bare `Gate` enum reference survives**

Run: `grep -rn "\bGate\b" --include="*.java" osgi/domain-annotations maven-embed-staging-ext | grep -v "StagingGate"`
Expected: no output. (Readiness-gate classes like `SeedSystemdAdapterEndpointGate` are a different word and live under `exec/`, not matched here.)

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(staging): rename Gate -> StagingGate (disambiguate from runtime gates/gateways)"
```

---

## Task 2: The `RealmBoundary` law + its referenced-type collector

**Files:**
- Create: `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/ReferencedTypes.java`
- Create: `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/RealmBoundary.java`
- Create: `maven-embed-staging-ext/staging-extension/src/test/java/io/nxmatic/rke2lab/maven/staging/ReferencedTypesTest.java`
- Create: `maven-embed-staging-ext/staging-extension/src/test/java/io/nxmatic/rke2lab/maven/staging/RealmBoundaryTest.java`

**Interfaces:**
- Consumes: `StagingGate` (Task 1); `ResolvedBundle.isOurs(String)` and `ResolvedBundle.OUR_ROOT` (existing).
- Produces:
  - `final class ReferencedTypes` with `static Set<String> in(byte[] classfile)` — every referenced type's **package name** (dotted) collected from a class's constant pool, field/method descriptors, signatures, and method bodies, filtered to `io.nxmatic.rke2lab.*` (our jurisdiction). Returns dotted package names, not type names, because the realm contract is package-scoped (a classloader resolves packages).
  - `final class RealmBoundary` with constructor `RealmBoundary(String realmLabel, Set<String> forbiddenPackages, Set<String> ownPackages)` and method `List<String> violations(String binaryName, byte[] classfile)` returning lines of the form `"<realmLabel> <Simple> references <forbidden.pkg>"` for each forbidden package the class references that is not in `ownPackages`. The caller (Task 3) accumulates across all classes of a carrier.

**Why this shape:** The three existing laws read a *jar* and police its *exported* surface with `SKIP_CODE`. `RealmBoundary` is different on both axes — it reads *bodies* (the drift is an `invokestatic` in a body) and it judges *references out*, not exports. Keeping the byte-level collector (`ReferencedTypes`) separate from the policy (`RealmBoundary`) lets each be unit-tested with synthetic bytecode, exactly like `RecordPurityTest`. With only core ASM available (no asm-commons `Remapper`), `ReferencedTypes` uses a `ClassVisitor` + `MethodVisitor` + `SignatureReader`.

- [ ] **Step 1: Write the failing test for `ReferencedTypes` — it collects a type referenced in a METHOD BODY**

Create `ReferencedTypesTest.java`. The decisive case: a class whose only mention of `io.nxmatic.rke2lab.doctor.records` is an `invokestatic` in a method body (the `Severity.parse()` shape) must still be collected — proving we do NOT use `SKIP_CODE`.

```java
package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ReferencedTypesTest {

  @Test
  void collectsATypeReferencedOnlyInAMethodBody() {
    // A class io.nxmatic.rke2lab.host.Policy whose from() invokes
    // io.nxmatic.rke2lab.doctor.records.Severity.parse(String) — the real drift shape.
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "io/nxmatic/rke2lab/host/Policy", null,
        "java/lang/Object", null);
    final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "from",
        "()V", null, null);
    mv.visitCode();
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "io/nxmatic/rke2lab/doctor/records/Severity",
        "parse", "(Ljava/lang/String;)Ljava/util/Optional;", false);
    mv.visitInsn(Opcodes.POP);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 0);
    mv.visitEnd();
    cw.visitEnd();

    final Set<String> pkgs = ReferencedTypes.in(cw.toByteArray());
    assertTrue(pkgs.contains("io.nxmatic.rke2lab.doctor.records"),
        "a type used only in a method body must be collected (no SKIP_CODE)");
  }

  @Test
  void filtersOutForeignAndJdkPackages() {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "io/nxmatic/rke2lab/host/Plain", null,
        "java/lang/Object", null);
    cw.visitField(Opcodes.ACC_PRIVATE, "log", "Lorg/slf4j/Logger;", null, null).visitEnd();
    cw.visitEnd();
    final Set<String> pkgs = ReferencedTypes.in(cw.toByteArray());
    assertFalse(pkgs.contains("org.slf4j"), "foreign packages are out of jurisdiction");
    assertFalse(pkgs.stream().anyMatch(p -> p.startsWith("java.")), "JDK is not ours");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `flox activate -- ./mvnw -pl :staging-extension test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ReferencedTypesTest`
Expected: FAIL — compilation error "cannot find symbol ReferencedTypes".

- [ ] **Step 3: Implement `ReferencedTypes`**

Create `ReferencedTypes.java`. Core ASM only: a `ClassVisitor` that records the super/interfaces, every field descriptor, every method descriptor + its body instructions (`visitMethodInsn`, `visitFieldInsn`, `visitTypeInsn`, `visitLdcInsn` of a `Type`), and generic signatures via `SignatureReader`. Each collected internal name is reduced to its dotted package and filtered to our root.

```java
package io.nxmatic.rke2lab.maven.staging;

import java.util.LinkedHashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Every package under our root ({@link ResolvedBundle#OUR_ROOT}) that a single class REFERENCES —
 * collected from the constant pool the way {@code jdeps} does: super/interfaces, field and method
 * descriptors, generic signatures, AND method bodies (type/field/method instructions, ldc of a
 * Type). Unlike the other gates this reads bodies (no {@code SKIP_CODE}) — the boundary drift it
 * feeds is {@code Severity.parse()}, an invokestatic inside a body. Foreign and JDK packages are
 * dropped: the staging gates judge only our code.
 */
final class ReferencedTypes {

  private ReferencedTypes() {}

  /** The dotted, our-root package names this class references. */
  static Set<String> in(byte[] classfile) {
    final Set<String> packages = new LinkedHashSet<>();
    new ClassReader(classfile)
        .accept(new Collector(packages), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return packages;
  }

  /** Add the package of every type named in {@code descriptor} (handles arrays + method types). */
  private static void addDescriptor(Set<String> packages, String descriptor) {
    if (descriptor == null) {
      return;
    }
    if (descriptor.charAt(0) == '(') {
      for (Type arg : Type.getArgumentTypes(descriptor)) {
        addType(packages, arg);
      }
      addType(packages, Type.getReturnType(descriptor));
    } else {
      addType(packages, Type.getType(descriptor));
    }
  }

  private static void addType(Set<String> packages, Type type) {
    Type t = type;
    while (t.getSort() == Type.ARRAY) {
      t = t.getElementType();
    }
    if (t.getSort() == Type.OBJECT) {
      addInternal(packages, t.getInternalName());
    }
  }

  /** Add the package of an internal name (e.g. {@code io/nxmatic/rke2lab/doctor/records/Severity}). */
  private static void addInternal(Set<String> packages, String internalName) {
    if (internalName == null) {
      return;
    }
    final int slash = internalName.lastIndexOf('/');
    if (slash < 0) {
      return;
    }
    final String pkg = internalName.substring(0, slash).replace('/', '.');
    if (ResolvedBundle.isOurs(pkg)) {
      packages.add(pkg);
    }
  }

  private static void addSignature(Set<String> packages, String signature) {
    if (signature == null) {
      return;
    }
    new SignatureReader(signature)
        .accept(
            new SignatureVisitor(Opcodes.ASM9) {
              @Override
              public void visitClassType(String name) {
                addInternal(packages, name);
              }
            });
  }

  private static final class Collector extends ClassVisitor {

    private final Set<String> packages;

    Collector(Set<String> packages) {
      super(Opcodes.ASM9);
      this.packages = packages;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
        String[] interfaces) {
      addInternal(packages, superName);
      if (interfaces != null) {
        for (String itf : interfaces) {
          addInternal(packages, itf);
        }
      }
      addSignature(packages, signature);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature,
        Object value) {
      addDescriptor(packages, descriptor);
      addSignature(packages, signature);
      return null;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
        String[] exceptions) {
      addDescriptor(packages, descriptor);
      addSignature(packages, signature);
      if (exceptions != null) {
        for (String ex : exceptions) {
          addInternal(packages, ex);
        }
      }
      return new BodyVisitor(packages);
    }
  }

  /** Reads a method body: type/field/method instructions, and ldc of a Type literal. */
  private static final class BodyVisitor extends MethodVisitor {

    private final Set<String> packages;

    BodyVisitor(Set<String> packages) {
      super(Opcodes.ASM9);
      this.packages = packages;
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      addInternal(packages, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      addInternal(packages, owner);
      addDescriptor(packages, descriptor);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
        boolean isInterface) {
      addInternal(packages, owner);
      addDescriptor(packages, descriptor);
    }

    @Override
    public void visitLdcInsn(Object value) {
      if (value instanceof Type type) {
        addType(packages, type);
      }
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
      addDescriptor(packages, descriptor);
    }
  }
}
```

- [ ] **Step 4: Run the `ReferencedTypes` test to verify it passes**

Run: `flox activate -- ./mvnw -pl :staging-extension test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ReferencedTypesTest`
Expected: PASS, `Tests run: 2, Failures: 0`.

- [ ] **Step 5: Write the failing test for `RealmBoundary`**

Create `RealmBoundaryTest.java`. It feeds `RealmBoundary` a forbidden set (a bundle-only package) and asserts a class referencing it is reported, while a class referencing only its own/visible package is clean.

```java
package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class RealmBoundaryTest {

  private static final String FORBIDDEN = "io.nxmatic.rke2lab.doctor.records";

  private static byte[] classReferencing(String ownerInternal) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "io/nxmatic/rke2lab/host/Policy", null,
        "java/lang/Object", null);
    final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "from",
        "()V", null, null);
    mv.visitCode();
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, ownerInternal, "parse",
        "(Ljava/lang/String;)Ljava/util/Optional;", false);
    mv.visitInsn(Opcodes.POP);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 0);
    mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  @Test
  void aFlatClassReferencingAForbiddenPackageLeaks() {
    final RealmBoundary gate = new RealmBoundary("flat", Set.of(FORBIDDEN),
        Set.of("io.nxmatic.rke2lab.host"));
    final List<String> v = gate.violations("io/nxmatic/rke2lab/host/Policy",
        classReferencing("io/nxmatic/rke2lab/doctor/records/Severity"));
    assertEquals(1, v.size(), "one leak expected");
    assertTrue(v.get(0).contains("flat") && v.get(0).contains("Policy")
        && v.get(0).contains(FORBIDDEN), "line carries realm + type + forbidden package");
  }

  @Test
  void aClassReferencingOnlyItsOwnPackageIsClean() {
    final RealmBoundary gate = new RealmBoundary("flat", Set.of(FORBIDDEN),
        Set.of("io.nxmatic.rke2lab.host"));
    final List<String> v = gate.violations("io/nxmatic/rke2lab/host/Policy",
        classReferencing("io/nxmatic/rke2lab/host/Helper"));
    assertTrue(v.isEmpty(), "a reference within the visible set is not a leak");
  }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `flox activate -- ./mvnw -pl :staging-extension test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=RealmBoundaryTest`
Expected: FAIL — "cannot find symbol RealmBoundary".

- [ ] **Step 7: Implement `RealmBoundary`**

Create `RealmBoundary.java`:

```java
package io.nxmatic.rke2lab.maven.staging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The build-time guard of the host↔OSGi boundary (the {@code REALM_BOUNDARY} law): a class may
 * reference only types reachable in its OWN classloader realm. Constructed for one realm with its
 * forbidden set (packages a class in this realm cannot load) and its own/visible packages; reports
 * each class that references a forbidden package. Unlike the export-surface gates it reads method
 * bodies (via {@link ReferencedTypes}) — the drift it catches is {@code Severity.parse()}, an
 * invokestatic in a body. The realm label is carried into each line so a violation is auto-attributed
 * (a {@code flat}-realm line is a host/seam leak; a bundle-realm line is an OSGi-internal leak). See
 * docs/architecture/osgi/staging-gates-governance-spec.adoc § REALM_BOUNDARY.
 */
final class RealmBoundary {

  private final String realmLabel;
  private final Set<String> forbiddenPackages;
  private final Set<String> visiblePackages;

  RealmBoundary(String realmLabel, Set<String> forbiddenPackages, Set<String> visiblePackages) {
    this.realmLabel = realmLabel;
    this.forbiddenPackages = forbiddenPackages;
    this.visiblePackages = visiblePackages;
  }

  /** The leak lines for one class: each forbidden package it references and cannot see. */
  List<String> violations(String binaryName, byte[] classfile) {
    final String simple = simpleName(binaryName);
    final List<String> lines = new ArrayList<>();
    for (String referenced : ReferencedTypes.in(classfile)) {
      if (visiblePackages.contains(referenced)) {
        continue; // reachable in this realm — fine.
      }
      if (forbiddenPackages.contains(referenced)) {
        lines.add(realmLabel + " " + simple + " references " + referenced);
      }
    }
    return lines;
  }

  private static String simpleName(String binaryName) {
    final int slash = binaryName.lastIndexOf('/');
    return slash < 0 ? binaryName : binaryName.substring(slash + 1);
  }
}
```

- [ ] **Step 8: Run the `RealmBoundary` test to verify it passes**

Run: `flox activate -- ./mvnw -pl :staging-extension test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=RealmBoundaryTest`
Expected: PASS, `Tests run: 2, Failures: 0`.

- [ ] **Step 9: Commit**

```bash
git add maven-embed-staging-ext/staging-extension/src
git commit -m "feat(staging): RealmBoundary law + ReferencedTypes collector (reads method bodies)"
```

---

## Task 3: Wire `REALM_BOUNDARY` into `enforceGates`, scanning carriers + the exec's own classes

**Files:**
- Modify: `osgi/domain-annotations/src/main/java/io/nxmatic/rke2lab/domain/annotations/StagingGate.java` (add the enum constant)
- Modify: `maven-embed-staging-ext/.../staging/StagingGate.java` (mirror the constant)
- Modify: `maven-embed-staging-ext/.../staging/ResolvedBundle.java` (read classes from a jar OR a dir; a `classEntries()` helper)
- Modify: `maven-embed-staging-ext/.../staging/StagingExecutionStrategy.java` (compute the forbidden set, build the flat-realm carrier from the project's `target/classes`, run the law per realm, record at the governed level)

**Interfaces:**
- Consumes: `RealmBoundary`, `ReferencedTypes` (Task 2); `StagingClosure` / `ResolvedBundle.isDomain()` / `ourExportedPackages()` (existing); `EmbedCapability.isDomain()` / `isSeam()` (existing).
- Produces: `StagingGate.REALM_BOUNDARY` (both enums); `ResolvedBundle.classEntries()` returning `List<ClassEntry>` where `record ClassEntry(String binaryName, byte[] bytes)`; the `enforceGates` accumulation of `REALM_BOUNDARY` lines into the existing `GateReport`. Task 4 supplies the `@GovernedBy(StagingGate.REALM_BOUNDARY, WARN)` anchors the report reads.

**Why:** The law engine (Task 2) is realm-agnostic. This task computes the two realms from what bundles declare — the forbidden set is the union of `ourExportedPackages()` of every `isDomain()` bundle; the flat realm's members are the exec's own `target/classes` + the flat-tail jars + the seams; each bundle realm's visible set is its imports + own packages. The exec's own classes are the new input the extension never read before (it only read resolved dependency jars).

- [ ] **Step 1: Add `REALM_BOUNDARY` to both enums**

In `osgi/domain-annotations/.../StagingGate.java`, add the constant and its javadoc bullet:

```java
public enum StagingGate {
  RECORD_PURITY,
  SPEC_COVERAGE,
  INSTANCE_DISCIPLINE,
  REALM_BOUNDARY
}
```

In `maven-embed-staging-ext/.../staging/StagingGate.java`, mirror it:

```java
enum StagingGate {
  RECORD_PURITY,
  SPEC_COVERAGE,
  INSTANCE_DISCIPLINE,
  REALM_BOUNDARY;
  // fromName unchanged
}
```

- [ ] **Step 2: Write the failing test — `ResolvedBundle` reads class entries from a jar**

Add to a new `ResolvedBundleClassEntriesTest.java`:

```java
package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class ResolvedBundleClassEntriesTest {

  @Test
  void readsEveryClassEntryFromTheJar(@TempDir File dir) throws IOException {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "io/nxmatic/rke2lab/ex/Foo", null,
        "java/lang/Object", null);
    cw.visitEnd();
    final Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    final File jar = new File(dir, "x.jar");
    try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
      out.putNextEntry(new ZipEntry("io/nxmatic/rke2lab/ex/Foo.class"));
      out.write(cw.toByteArray());
      out.closeEntry();
    }
    final var entries = ResolvedBundle.read("g", "a", "1", jar).classEntries();
    assertTrue(entries.stream().anyMatch(e -> e.binaryName().equals("io/nxmatic/rke2lab/ex/Foo")));
  }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `flox activate -- ./mvnw -pl :staging-extension test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ResolvedBundleClassEntriesTest`
Expected: FAIL — "cannot find symbol classEntries".

- [ ] **Step 4: Implement `classEntries()` on `ResolvedBundle`**

Add a nested record and method. It reads the bundle's jar `file()` and returns every top-level+nested `.class` (we need bodies of all classes, including nested — a leak can live in an inner class):

```java
  /** One compiled class read from this carrier — its binary name and bytes. */
  public record ClassEntry(String binaryName, byte[] bytes) {}

  /** Every {@code .class} in this carrier's jar (top-level and nested), for body-level scans. */
  public java.util.List<ClassEntry> classEntries() {
    if (file() == null || !file().isFile()) {
      return java.util.List.of();
    }
    final java.util.List<ClassEntry> entries = new java.util.ArrayList<>();
    try (JarFile jar = new JarFile(file())) {
      final java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries();
      while (e.hasMoreElements()) {
        final java.util.jar.JarEntry entry = e.nextElement();
        final String name = entry.getName();
        if (!name.endsWith(".class") || name.endsWith("module-info.class")) {
          continue;
        }
        try (var in = jar.getInputStream(entry)) {
          entries.add(new ClassEntry(name.substring(0, name.length() - 6), in.readAllBytes()));
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read classes of " + ga(), ex);
    }
    return entries;
  }

  /** Every {@code .class} under an exploded classes directory (the exec's own target/classes). */
  public static java.util.List<ClassEntry> classEntriesOf(java.nio.file.Path classesDir) {
    if (classesDir == null || !java.nio.file.Files.isDirectory(classesDir)) {
      return java.util.List.of();
    }
    try (var tree = java.nio.file.Files.walk(classesDir)) {
      final java.util.List<ClassEntry> entries = new java.util.ArrayList<>();
      tree.filter(p -> p.toString().endsWith(".class"))
          .filter(p -> !p.getFileName().toString().equals("module-info.class"))
          .forEach(p -> {
            try {
              final String binary =
                  classesDir.relativize(p).toString().replace('\\', '/');
              entries.add(new ClassEntry(
                  binary.substring(0, binary.length() - 6),
                  java.nio.file.Files.readAllBytes(p)));
            } catch (IOException ex) {
              throw new UncheckedIOException("cannot read class " + p, ex);
            }
          });
      return entries;
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot walk classes dir " + classesDir, ex);
    }
  }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `flox activate -- ./mvnw -pl :staging-extension test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ResolvedBundleClassEntriesTest`
Expected: PASS.

- [ ] **Step 6: Thread `MavenSession` into `enforceGates`, then add the realm computation + `REALM_BOUNDARY` enforcement**

First fix the signature: `enforceGates` does NOT currently receive the `MavenSession` (it takes `(List<ResolvedBundle> resolved, Path docsDir)`), but the flat-realm scan needs the project's `target/classes` and coordinates, which only the session carries. Change the signature and its single call site in `reconfigureStaging`:

```java
// call site (in reconfigureStaging, currently: enforceGates(resolved, locateDocsDir(session));)
    enforceGates(session, resolved, locateDocsDir(session));

// signature (currently: private void enforceGates(List<ResolvedBundle> resolved, Path docsDir) …)
  private void enforceGates(
      MavenSession session, List<ResolvedBundle> resolved, java.nio.file.Path docsDir)
      throws LifecycleExecutionException {
```

Then, after the existing per-bundle loop (but before `report.flush()`), add the realm scan. The forbidden set is the union of every `isDomain()` bundle's `ourExportedPackages()`. The flat realm = exec `target/classes` (from `session.getCurrentProject().getBuild().getOutputDirectory()`) + every flat-tail carrier (not `isDomain`, not the launcher) + the seams; its visible set = all flat packages + system. Each bundle realm = one `isDomain` carrier; its visible set = its own exported+imported packages.

```java
  // ---- REALM_BOUNDARY: no class references a type unreachable in its realm ----
  final Set<String> forbidden = new java.util.LinkedHashSet<>();
  for (ResolvedBundle b : resolved) {
    if (b.embed() != null && b.embed().isDomain()) {
      forbidden.addAll(b.ourExportedPackages());
    }
  }
  if (!forbidden.isEmpty()) {
    // Flat realm: the exec's own classes + the flat tail + the seams. Its visible set is every
    // package that loads flat (our flat packages + the seam exports); a forbidden (bundle-only)
    // package referenced from here cannot be loaded by the flat host classloader at runtime.
    final Set<String> flatVisible = new java.util.LinkedHashSet<>();
    final List<ResolvedBundle.ClassEntry> flatClasses = new ArrayList<>();
    final java.nio.file.Path ownClasses =
        java.nio.file.Path.of(
            session.getCurrentProject().getBuild().getOutputDirectory());
    flatClasses.addAll(ResolvedBundle.classEntriesOf(ownClasses));
    for (ResolvedBundle b : resolved) {
      final boolean domain = b.embed() != null && b.embed().isDomain();
      if (b.launcher() || domain) {
        continue; // the framework, and bundle-side domains, are not in the flat realm.
      }
      flatVisible.addAll(b.ourExportedPackages());
      flatClasses.addAll(b.classEntries()); // includes the seams (type=seam) — they are flat too.
    }
    final RealmBoundary flat = new RealmBoundary("flat", forbidden, flatVisible);
    final List<String> flatViolations = new ArrayList<>();
    for (ResolvedBundle.ClassEntry c : flatClasses) {
      flatViolations.addAll(flat.violations(c.binaryName(), c.bytes()));
    }
    // Attribute every flat-realm violation at the governance of the exec project (its package-info).
    report.record(
        StagingGate.REALM_BOUNDARY,
        execGovernance(session),
        execPseudoBundle(session),
        flatViolations,
        "flat-realm classes reference bundle-only packages (host/seam leak)");

    // Bundle realms: each isDomain carrier may reference only its own + imported + system packages.
    for (ResolvedBundle b : resolved) {
      if (b.embed() == null || !b.embed().isDomain()) {
        continue;
      }
      final Set<String> visible = new java.util.LinkedHashSet<>(b.ourExportedPackages());
      visible.addAll(b.imports().names());
      final RealmBoundary realm = new RealmBoundary("bundle:" + b.symbolicName(), forbidden, visible);
      final List<String> v = new ArrayList<>();
      for (ResolvedBundle.ClassEntry c : b.classEntries()) {
        v.addAll(realm.violations(c.binaryName(), c.bytes()));
      }
      report.record(
          StagingGate.REALM_BOUNDARY,
          b.governance().levels(),
          b,
          v,
          "bundle-realm classes reference packages they do not import (OSGi-internal leak)");
    }
  }
```

Add the two helpers (`execGovernance` reads the current project's `target/classes` package-info for its level map; `execPseudoBundle` builds a `ResolvedBundle` view of the exec for the report label):

```java
  /** The current exec project's governance, read from its own compiled package-info classes. */
  private Map<StagingGate, EnforcementLevel> execGovernance(MavenSession session) {
    final java.nio.file.Path classes =
        java.nio.file.Path.of(session.getCurrentProject().getBuild().getOutputDirectory());
    final Map<StagingGate, EnforcementLevel> levels =
        new java.util.EnumMap<>(StagingGate.class);
    for (ResolvedBundle.ClassEntry c : ResolvedBundle.classEntriesOf(classes)) {
      if (c.binaryName().endsWith("package-info")) {
        GovernanceReader.readInto(c.bytes(), levels);
      }
    }
    return levels;
  }

  /** A ResolvedBundle view of the exec module itself, for the report's ga() label. */
  private ResolvedBundle execPseudoBundle(MavenSession session) {
    final MavenProject p = session.getCurrentProject();
    return ResolvedBundle.read(p.getGroupId(), p.getArtifactId(), p.getVersion(), null);
  }
```

- [ ] **Step 7: Add `GovernanceReader.readInto(byte[], Map)` (read a package-info's levels from raw bytes)**

`GovernanceReader` currently reads from a jar. Add a static entry that parses one package-info's bytes into a levels map (reused by `execGovernance`):

```java
  /** Parse one package-info's bytes, merging its @GovernedBy poses into {@code levels}. */
  static void readInto(byte[] packageInfo, Map<StagingGate, EnforcementLevel> levels) {
    new ClassReader(packageInfo)
        .accept(new GovernanceVisitor(levels),
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
  }
```

- [ ] **Step 8: Build the staging-extension and run its full test suite**

Run: `flox activate -- ./mvnw -pl :staging-extension -am test -DskipTests=false -Dmaven.build.cache.skipCache=true`
Expected: `BUILD SUCCESS`, all gate tests green (no regression in RecordPurity/SpecCoverage/InstanceDiscipline/Governance, plus the new RealmBoundary/ReferencedTypes/ClassEntries tests).

- [ ] **Step 9: Commit**

```bash
git add osgi/domain-annotations/src maven-embed-staging-ext/staging-extension/src
git commit -m "feat(staging): wire REALM_BOUNDARY into enforceGates over flat + bundle realms"
```

---

## Task 4: Governance anchors + POM wiring (gate live at WARN, prints the worklist)

**Files:**
- Modify: `exec/seed-master/pom.xml`
- Create: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/package-info.java`
- Create or modify: `osgi/doctor/doctor-port/src/main/java/io/nxmatic/rke2lab/doctor/port/package-info.java`
- Modify: `osgi/cluster/cluster-port/src/main/java/io/nxmatic/rke2lab/cluster/port/package-info.java`

**Interfaces:**
- Consumes: `StagingGate.REALM_BOUNDARY` (Task 3); the `enforceGates` flat-realm scan that reads the exec's package-info governance (Task 3 step 7).
- Produces: the `@GovernedBy(StagingGate.REALM_BOUNDARY, WARN)` anchors that make the gate list (not fail) during Plan 2's migration. No type surface for later tasks.

**Why:** Without a governance anchor the gate defaults to `ERROR` and would break the build immediately — but the surface is not migrated yet (that is Plan 2). `WARN` makes the gate a visible, shrinking worklist. The host exec module has no `package-info` and no dependency on `domain-annotations`; both must be added (the direct-dependency discipline: the dep resolves through the reactor, never via `~/.m2`). The two seams already have a package-info; they gain the `REALM_BOUNDARY` pose.

- [ ] **Step 1: Add `domain-annotations` as a direct dependency of seed-master**

In `exec/seed-master/pom.xml`, add to `<dependencies>` (version from the reactor/BOM, no explicit version):

```xml
    <dependency>
      <groupId>io.nxmatic.rke2lab</groupId>
      <artifactId>domain-annotations</artifactId>
    </dependency>
```

- [ ] **Step 2: Create the exec governance anchor**

Create `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/package-info.java`:

```java
/**
 * The flat host control-plane. Governed at {@code REALM_BOUNDARY = WARN} while the host↔OSGi surface
 * is migrated to Documents (see docs/architecture/osgi/world-exchange-spec.adoc): the gate lists every
 * flat-realm class still referencing a bundle-only doctor.records type as a shrinking worklist, build
 * green. Drop this pose to return to the locked ERROR default once the migration (Plan 2) is complete.
 */
@GovernedBy(value = StagingGate.REALM_BOUNDARY, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
```

- [ ] **Step 3: Add the `REALM_BOUNDARY` WARN pose to `cluster-port`**

`osgi/cluster/cluster-port/.../cluster/port/package-info.java` already poses SPEC_COVERAGE=WARN. Add a second pose (the `@GovernedBy` is `@Repeatable`):

```java
@org.osgi.annotation.versioning.Version("1.0.0")
@GovernedBy(value = StagingGate.SPEC_COVERAGE, level = EnforcementLevel.WARN)
// REALM_BOUNDARY WARN: this seam still imports doctor.records (the boundary leak) — listed while the
// surface migrates to Documents; drop to return to the ERROR default once the import is gone.
@GovernedBy(value = StagingGate.REALM_BOUNDARY, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.cluster.port;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
```

- [ ] **Step 4a: Add `domain-annotations` as a direct dependency of doctor-port**

`osgi/doctor/doctor-port/pom.xml` does NOT yet depend on `domain-annotations` (confirmed; cluster-port already does). Add to its `<dependencies>` (no explicit version — resolves through the reactor):

```xml
    <dependency>
      <groupId>io.nxmatic.rke2lab</groupId>
      <artifactId>domain-annotations</artifactId>
    </dependency>
```

- [ ] **Step 4b: Add the `REALM_BOUNDARY` WARN pose to doctor-port's existing package-info**

`osgi/doctor/doctor-port/.../doctor/port/package-info.java` currently holds only the `@Version` line (2 lines). Replace it with the governed form:

```java
@org.osgi.annotation.versioning.Version("1.0.0")
// REALM_BOUNDARY WARN: this seam imports doctor.records (the boundary leak #1) — listed while the
// surface migrates to Documents; drop to return to the ERROR default once the import is gone.
@GovernedBy(value = StagingGate.REALM_BOUNDARY, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
```

- [ ] **Step 5: Full reactor build that runs staging — the gate prints the worklist, build stays green**

Run: `flox activate -- ./mvnw package -Pall-worlds -DskipTests -Dmaven.build.cache.skipCache=true 2>&1 | tee /tmp/realm-boundary.log`
Expected: `BUILD SUCCESS`. The staging log shows the gate summary line `realm-boundary: 0 error, N warn` and a `[osgi-staging] … staging-law violation(s) at WARN` block listing the flat-realm host files (e.g. `flat ControlplanePolicy references io.nxmatic.rke2lab.doctor.records`) and the two seams (`bundle:…doctor.port`/`cluster.port`). N > 0, all at WARN, zero ERROR.

- [ ] **Step 6: Verify the worklist is non-empty and attributed (this IS the input to Plan 2)**

Run: `grep -E "\[realm-boundary\]|realm-boundary:" /tmp/realm-boundary.log`
Expected: a `realm-boundary: 0 error, N warn` summary (N ≥ the ~19 host files + 2 seams), and WARN lines naming `flat …` (host/seam leaks) — the migration worklist. If the summary shows `0 warn`, the gate is not seeing the exec's own classes — re-check Task 3 step 6 (`getOutputDirectory`) before proceeding.

- [ ] **Step 7: Commit**

```bash
git add exec/seed-master/pom.xml exec/seed-master/src osgi/doctor/doctor-port/pom.xml osgi/doctor/doctor-port/src osgi/cluster/cluster-port/src
git commit -m "feat(staging): govern REALM_BOUNDARY at WARN; seed-master + seams anchored (the migration worklist)"
```

---

## Self-review checklist (run after writing — see end of plan)

This plan delivers step 1 of the world-exchange sequencing (the gate + the two renames). Plan 2 (the Document migration) is written separately, FROM the worklist this gate prints — so its host edits are exact, not guessed.
