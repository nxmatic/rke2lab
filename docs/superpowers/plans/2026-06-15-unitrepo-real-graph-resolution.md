# Unitrepo Real-Graph Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the standalone Felix `UnitResolver` computes the real rke2lab cross-layer dependency closure (reactor modules + manifest domains + manifest units) in a single `resolve()` pass, replacing the synthetic fixtures in `UnitResolverTest`.

**Architecture:** Add one generic verb (`requireAll`, OSGi `cardinality:=multiple`) to `unitrepo-core` production code. Everything rke2lab-specific lives in **seed-master test scope**: a hand-transcribed `ReactorModuleCatalog` (coarse/module layer), a `ManifestsUniverse` adapter that reads the real `ManifestsDomainRegistry` (fine/domain+unit layer), a `UniverseBuilder` that merges both into one `List<UnitResource>`, and a `RealGraphResolutionTest` that resolves from the `seed-master` module-unit and asserts the cross-layer closure. V1 stays **latent** — the harness reads structure already in the code; it never modifies the 28 unit / 10 domain / 10 registrar classes, and does not retire the existing walker.

**Tech Stack:** Java 25 (flox toolchain), Maven reactor (`-am`, never `install`), Apache Felix Resolver 2.0.4, `org.osgi:osgi.core:8.0.0`, JUnit 5 (inherited test-scope from parent pom).

---

## Build & verification conventions (read once, apply to every task)

- **All Maven runs go through flox:** `flox activate -- ./mvnw …`
- **Always `-am`** so siblings build from the reactor source, never installed jars.
- **Tests are skipped by default** (root `.mvn` config forces `-DskipTests`). To execute them, pass `-DskipTests=false`. A `BUILD SUCCESS` with no `Tests run:` line means they were **skipped, not passed**.
- **Build-cache lies:** add `-Dmaven.build.cache.skipCache=true` when you need a guaranteed real run.
- **Verify by COUNTING surefire reports**, never by trusting console filtering. The canonical command for a single module's tests:

  ```bash
  flox activate -- ./mvnw clean package -pl :<module> -am \
    -Dmaven.build.cache.skipCache=true -DskipTests=false
  ```

  Then confirm the report exists and shows the expected counts:

  ```bash
  cat <module>/target/surefire-reports/*<TestClass>*.txt
  ```

- **CLI module selector is the unprefixed dir name:** `-pl :unitrepo-core`, `-pl :seed-master`.
- **Worktree:** all work happens in `.claude/worktrees/feature+unitrepo-resolution-core` on branch `feature/unitrepo-resolution-core`. The `.flox/env/manifest.lock` shows as modified — that is expected re-smudge noise; **do not stage it**.

---

## File Structure

| File | Module | Responsibility | Action |
|---|---|---|---|
| `unitrepo-core/src/main/java/io/nxmatic/rke2lab/unitrepo/core/UnitResource.java` | unitrepo-core (prod) | Add `requireAll(ns, filter)` verb setting `cardinality:=multiple` | Modify |
| `unitrepo-core/src/test/java/io/nxmatic/rke2lab/unitrepo/core/UnitResolverTest.java` | unitrepo-core (test) | Add a unit test proving `requireAll` fans out to >1 provider | Modify |
| `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ReactorModuleCatalog.java` | seed-master (test) | Coarse layer: 8 module-units + Maven edges, hand-transcribed | Create |
| `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ManifestsUniverse.java` | seed-master (test) | Fine layer: adapter reading the real `ManifestsDomainRegistry` → domain + unit `UnitResource`s | Create |
| `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/UniverseBuilder.java` | seed-master (test) | Merge both layers into one universe + expose `byId` for assertions | Create |
| `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/RealGraphResolutionTest.java` | seed-master (test) | The proof: resolve from seed-master, assert cross-layer closure + unsatisfiable-throws | Create |
| `seed-master/pom.xml` | seed-master | Add test-scope dep on `unitrepo-core` | Modify |

**Namespaces (string constants used across the harness):**
- `unitrepo.module` — attribute `module`
- `unitrepo.manifest.domain` — attributes `domain`, `module`
- `unitrepo.unit` — attributes `unit`, `domain`

**OSGi cardinality (verified against `osgi.core:8.0.0`):**
- `org.osgi.resource.Namespace.REQUIREMENT_CARDINALITY_DIRECTIVE` = `"cardinality"`
- `org.osgi.resource.Namespace.CARDINALITY_MULTIPLE` = `"multiple"`

---

## Task 1: Add the `requireAll` verb to `UnitResource` (the sole production change)

**Files:**
- Modify: `unitrepo-core/src/main/java/io/nxmatic/rke2lab/unitrepo/core/UnitResource.java`
- Test: `unitrepo-core/src/test/java/io/nxmatic/rke2lab/unitrepo/core/UnitResolverTest.java`

The existing `require(ns, filter)` sets only the `filter` directive, so Felix wires exactly one provider per requirement. `requireAll` adds `cardinality:=multiple` so the resolver wires every match. `findProviders` already returns all matches (no change needed there).

- [ ] **Step 1: Write the failing test**

Add this test method to `UnitResolverTest` (alongside the existing two). It builds three providers of one namespace and asserts a single `requireAll` requirement wires all three:

```java
  @Test
  void requireAllWiresEveryMatchingProvider() throws ResolutionException {
    UnitResource memberA =
        new UnitResource("member-a").provide(NS_DOMAIN, Map.of("group", "g1"));
    UnitResource memberB =
        new UnitResource("member-b").provide(NS_DOMAIN, Map.of("group", "g1"));
    UnitResource memberC =
        new UnitResource("member-c").provide(NS_DOMAIN, Map.of("group", "g1"));

    UnitResource parent =
        new UnitResource("parent").requireAll(NS_DOMAIN, "(group=g1)");

    UnitResolver resolver =
        new UnitResolver(List.of(memberA, memberB, memberC, parent));
    Map<Resource, List<Wire>> wiring = resolver.resolve(parent);

    // parent must wire to ALL three members (cardinality:=multiple), not just one
    assertEquals(3, wiring.get(parent).size(), "requireAll fans out to every match");
    assertEquals(4, wiring.size(), "closure = parent + three members");
  }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
flox activate -- ./mvnw clean test -pl :unitrepo-core -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=UnitResolverTest#requireAllWiresEveryMatchingProvider
```

Expected: **compile failure** — `cannot find symbol: method requireAll(String,String)`.

- [ ] **Step 3: Implement `requireAll`**

In `UnitResource.java`, add the OSGi import and the new verb directly after the existing `require` method.

Add to the imports block:

```java
import org.osgi.resource.Namespace;
```

Add the method (place it right after `require`):

```java
  /**
   * Require <em>every</em> capability matching the {@code filter:} ({@code cardinality:=multiple}).
   * Used for containment edges: a parent gathers all its members with one requirement.
   */
  public UnitResource requireAll(String namespace, String filter) {
    requirements.add(
        new UnitRequirement(
            namespace,
            Map.of(
                "filter", filter,
                Namespace.REQUIREMENT_CARDINALITY_DIRECTIVE, Namespace.CARDINALITY_MULTIPLE),
            Map.of(),
            this));
    return this;
  }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
flox activate -- ./mvnw clean test -pl :unitrepo-core -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=UnitResolverTest
```

Expected: PASS. Confirm by counting the surefire report:

```bash
cat unitrepo-core/target/surefire-reports/*UnitResolverTest*.txt | grep -E 'Tests run'
```

Expected line: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` (the original 2 + the new 1).

- [ ] **Step 5: Commit**

```bash
git add unitrepo-core/src/main/java/io/nxmatic/rke2lab/unitrepo/core/UnitResource.java \
        unitrepo-core/src/test/java/io/nxmatic/rke2lab/unitrepo/core/UnitResolverTest.java
git commit -m "feat(unitrepo): requireAll verb — cardinality multiple wires every match"
```

---

## Task 2: Add the test-scope `unitrepo-core` dependency to seed-master

**Files:**
- Modify: `seed-master/pom.xml`

The proof harness lives in seed-master test scope and needs `UnitResource` / `UnitResolver`. seed-master already depends on `manifests` (compile scope), so the fine layer is reachable; this task adds the resolver types.

- [ ] **Step 1: Add the dependency**

In `seed-master/pom.xml`, inside `<dependencies>`, add (group/version follow the reactor convention used by sibling deps):

```xml
    <dependency>
      <groupId>io.nxmatic.rke2lab</groupId>
      <artifactId>unitrepo-core</artifactId>
      <version>${project.version}</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Verify the reactor still resolves and seed-master compiles**

```bash
flox activate -- ./mvnw clean test-compile -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true
```

Expected: `BUILD SUCCESS`. (No tests run yet — this only confirms the new dep resolves through the reactor and test sources compile.)

- [ ] **Step 3: Commit**

```bash
git add seed-master/pom.xml
git commit -m "build(seed-master): test-scope dep on unitrepo-core for the real-graph proof"
```

---

## Task 3: `ReactorModuleCatalog` — the coarse (module) layer

**Files:**
- Create: `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ReactorModuleCatalog.java`

Hand-transcribed from the reactor poms (verified 2026-06-15). The module→module edges in seed-master's closure:

```
manifests           -> cdk8s-systemd, netplan
pulumi-automation-ext-testkit -> pulumi-automation-ext
seed-master         -> incus, manifests, netplan, pulumi-automation-ext,
                       pulumi-automation-ext-testkit, systemd-contract
```

Leaves (no rke2lab deps): `bom`, `netplan`, `systemd-contract`, `cdk8s-systemd`, `incus`, `pulumi-automation-ext`. (We model the 8 modules that participate in seed-master's closure; `bom` is a pure dependency-management pom and is intentionally excluded from the universe.)

This task only builds the catalog and asserts its own integrity (the resolution test comes later), so it is testable in isolation.

- [ ] **Step 1: Write the failing test**

Create the test file at the same time (so the catalog has a test). Create
`seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ReactorModuleCatalogTest.java`:

```java
package io.nxmatic.rke2lab.unitrepo.realgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReactorModuleCatalogTest {

  @Test
  void emitsAModuleUnitPerModuleWithRealEdges() {
    ReactorModuleCatalog catalog = new ReactorModuleCatalog();
    Map<String, UnitResource> byId = catalog.byId();

    // the 8 modules in seed-master's closure are present
    assertEquals(8, byId.size(), "8 module-units modeled");
    assertTrue(byId.containsKey("seed-master"));
    assertTrue(byId.containsKey("manifests"));
    assertTrue(byId.containsKey("cdk8s-systemd"));
    assertTrue(byId.containsKey("netplan"));

    // every module provides the unitrepo.module capability carrying its own id
    UnitResource seedMaster = byId.get("seed-master");
    List<Capability> moduleCaps = seedMaster.getCapabilities(ReactorModuleCatalog.NS_MODULE);
    assertEquals(1, moduleCaps.size(), "one unitrepo.module capability");
    assertEquals("seed-master", moduleCaps.get(0).getAttributes().get("module"));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
flox activate -- ./mvnw clean test -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=ReactorModuleCatalogTest
```

Expected: **compile failure** — `ReactorModuleCatalog` does not exist.

- [ ] **Step 3: Implement `ReactorModuleCatalog`**

Create `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ReactorModuleCatalog.java`:

```java
package io.nxmatic.rke2lab.unitrepo.realgraph;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The coarse (module) layer of the real-graph universe: one {@link UnitResource} per reactor module
 * that participates in seed-master's closure, with module-to-module edges transcribed faithfully
 * from the reactor poms (verified 2026-06-15). Same hardcoding discipline as {@code
 * ManifestDomainCatalog}. Test-scope only — the proof reads structure already in the build, it does
 * not introspect Maven at runtime.
 */
final class ReactorModuleCatalog {

  static final String NS_MODULE = "unitrepo.module";

  private final Map<String, UnitResource> byId = new LinkedHashMap<>();

  ReactorModuleCatalog() {
    // leaves first (no rke2lab deps)
    module("netplan");
    module("systemd-contract");
    module("cdk8s-systemd");
    module("incus");
    module("pulumi-automation-ext");
    // modules with edges
    module("pulumi-automation-ext-testkit", "pulumi-automation-ext");
    module("manifests", "cdk8s-systemd", "netplan");
    module(
        "seed-master",
        "incus",
        "manifests",
        "netplan",
        "pulumi-automation-ext",
        "pulumi-automation-ext-testkit",
        "systemd-contract");
  }

  private void module(String id, String... dependsOnModuleIds) {
    UnitResource unit = new UnitResource(id).provide(NS_MODULE, Map.of("module", id));
    for (String dep : dependsOnModuleIds) {
      unit.require(NS_MODULE, "(module=" + dep + ")");
    }
    byId.put(id, unit);
  }

  Map<String, UnitResource> byId() {
    return Map.copyOf(byId);
  }

  List<UnitResource> all() {
    return List.copyOf(byId.values());
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
flox activate -- ./mvnw clean test -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=ReactorModuleCatalogTest
```

Expected: PASS. Confirm via surefire report:

```bash
cat seed-master/target/surefire-reports/*ReactorModuleCatalogTest*.txt | grep -E 'Tests run'
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Commit**

```bash
git add seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ReactorModuleCatalog.java \
        seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ReactorModuleCatalogTest.java
git commit -m "test(unitrepo): ReactorModuleCatalog — coarse module layer from real poms"
```

---

## Task 4: `ManifestsUniverse` — the fine (domain + unit) layer adapter

**Files:**
- Create: `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ManifestsUniverse.java`

Reads the **real** assembled `ManifestsDomainRegistry` (built from an all-enabled policy + the 10 public registrars) and emits:
- a domain-unit per domain, providing `unitrepo.manifest.domain` (`domain=<id>`) **and** the membership marker `module=manifests`; requiring `unitrepo.manifest.domain` for each `dependsOnDomainIds()` entry; and `requireAll unitrepo.unit` for its members (`domain=<id>`).
- a manifest-unit per unit, providing `unitrepo.unit` (`unit=<id>`) **and** `domain=<its domain>`; requiring `unitrepo.unit` for each `dependsOnManifestsUnitIds()` entry.

The module-unit `manifests` (from Task 3) must additionally gather its domains, so this adapter also exposes a way to attach the `requireAll unitrepo.manifest.domain` (`module=manifests`) edge — done in `UniverseBuilder` (Task 5) to keep this adapter purely a registry→units mapping.

Verified API (manifests module):
- `ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build()` → `.all()` returns `List<String>` of domain ids.
- `ManifestDomainPolicy.builder().enableOnly(Iterable<String>).build()`.
- `new ManifestsDomainRegistryBuilder().register(new XxxDomainRegistrar(), policy)…build()` → `ManifestsDomainRegistry`.
- `ManifestsDomainRegistry.domains()` → `List<ManifestsDomain>`; `.manifestUnits()` → `List<ManifestsUnit>`; `.requireDomainIdForManifestsUnit(id)` → `String`.
- `ManifestsDomain.domainId()`, `.dependsOnDomainIds()` (`List<String>`), `.units()` (`List<? extends ManifestsUnit>`).
- `ManifestsUnit.manifestUnitId()`, `.dependsOnManifestsUnitIds()` (`List<String>`).

- [ ] **Step 1: Write the failing test**

Create `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ManifestsUniverseTest.java`:

```java
package io.nxmatic.rke2lab.unitrepo.realgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestsUniverseTest {

  @Test
  void emitsDomainAndUnitResourcesFromTheRealRegistry() {
    ManifestsUniverse universe = new ManifestsUniverse();

    Map<String, UnitResource> domains = universe.domainsById();
    Map<String, UnitResource> units = universe.unitsById();

    // the 10 real domains and the real flux gitops chain are present
    assertEquals(10, domains.size(), "10 manifest domains");
    assertTrue(domains.containsKey("gitops"));
    assertTrue(domains.containsKey("platform"));

    assertTrue(units.containsKey("gitops/flux-root"));
    assertTrue(units.containsKey("gitops/flux-instance"));
    assertTrue(units.containsKey("gitops/flux-operator"));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
flox activate -- ./mvnw clean test -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=ManifestsUniverseTest
```

Expected: **compile failure** — `ManifestsUniverse` does not exist.

- [ ] **Step 3: Implement `ManifestsUniverse`**

Create `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ManifestsUniverse.java`:

```java
package io.nxmatic.rke2lab.unitrepo.realgraph;

import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistry;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistryBuilder;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
import io.nxmatic.rke2lab.manifests.domain.CicdDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.ClusterApiDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.ClusterDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.GitopsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.HighAvailabilityDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.MeshDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.NetworkingDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.PlatformDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.RuntimeDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.StorageDomainRegistrar;
import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fine (domain + unit) layer of the real-graph universe: reads the <em>real</em> assembled
 * {@link ManifestsDomainRegistry} (all-enabled policy + the 10 public registrars) and re-expresses
 * its domains and units as {@link UnitResource}s in the resolver's vocabulary. Latent: it reads the
 * registry, never modifies a unit or domain class.
 */
final class ManifestsUniverse {

  static final String NS_DOMAIN = "unitrepo.manifest.domain";
  static final String NS_UNIT = "unitrepo.unit";
  static final String MANIFESTS_MODULE = "manifests";

  private final Map<String, UnitResource> domainsById = new LinkedHashMap<>();
  private final Map<String, UnitResource> unitsById = new LinkedHashMap<>();

  ManifestsUniverse() {
    ManifestsDomainRegistry registry = buildRealRegistry();

    for (ManifestsDomain domain : registry.domains()) {
      String id = domain.domainId();
      UnitResource domainUnit =
          new UnitResource(id)
              .provide(NS_DOMAIN, Map.of("domain", id, "module", MANIFESTS_MODULE));
      for (String dep : domain.dependsOnDomainIds()) {
        domainUnit.require(NS_DOMAIN, "(domain=" + dep + ")");
      }
      // gather this domain's members (containment, cardinality:=multiple)
      domainUnit.requireAll(NS_UNIT, "(domain=" + id + ")");
      domainsById.put(id, domainUnit);
    }

    for (ManifestsUnit unit : registry.manifestUnits()) {
      String id = unit.manifestUnitId();
      String domainId = registry.requireDomainIdForManifestsUnit(id);
      UnitResource unitResource =
          new UnitResource(id).provide(NS_UNIT, Map.of("unit", id, "domain", domainId));
      for (String dep : unit.dependsOnManifestsUnitIds()) {
        unitResource.require(NS_UNIT, "(unit=" + dep + ")");
      }
      unitsById.put(id, unitResource);
    }
  }

  private static ManifestsDomainRegistry buildRealRegistry() {
    ManifestDomainCatalog catalog =
        ManifestDomainCatalog.builder()
            .addDefaultDomains()
            .addDefaultStageALinkableDomains()
            .build();
    ManifestDomainPolicy allEnabled =
        ManifestDomainPolicy.builder().enableOnly(catalog.all()).build();
    return new ManifestsDomainRegistryBuilder()
        .register(new ClusterDomainRegistrar(), allEnabled)
        .register(new StorageDomainRegistrar(), allEnabled)
        .register(new GitopsDomainRegistrar(), allEnabled)
        .register(new RuntimeDomainRegistrar(), allEnabled)
        .register(new NetworkingDomainRegistrar(), allEnabled)
        .register(new MeshDomainRegistrar(), allEnabled)
        .register(new HighAvailabilityDomainRegistrar(), allEnabled)
        .register(new CicdDomainRegistrar(), allEnabled)
        .register(new ClusterApiDomainRegistrar(), allEnabled)
        .register(new PlatformDomainRegistrar(), allEnabled)
        .build();
  }

  Map<String, UnitResource> domainsById() {
    return Map.copyOf(domainsById);
  }

  Map<String, UnitResource> unitsById() {
    return Map.copyOf(unitsById);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
flox activate -- ./mvnw clean test -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=ManifestsUniverseTest
```

Expected: PASS. Confirm via surefire report:

```bash
cat seed-master/target/surefire-reports/*ManifestsUniverseTest*.txt | grep -E 'Tests run'
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.

> If `domains.size()` is not 10, the all-enabled policy or registrar set is wrong — re-check against `DefaultManifestSynthesisService.buildDomainRegistry` (it registers exactly these 10). Do not adjust the assertion to match a wrong number.

- [ ] **Step 5: Commit**

```bash
git add seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ManifestsUniverse.java \
        seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/ManifestsUniverseTest.java
git commit -m "test(unitrepo): ManifestsUniverse — fine layer adapter over the real registry"
```

---

## Task 5: `UniverseBuilder` — merge both layers into one universe

**Files:**
- Create: `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/UniverseBuilder.java`

Merges the coarse and fine layers and attaches the one cross-layer containment edge that spans them: the `manifests` module-unit gathers all manifest domains via `requireAll unitrepo.manifest.domain` (`module=manifests`). Exposes a combined `byId` map so the resolution test can correlate wiring results back to ids. The merge reuses the id→UnitResource maps the layer builders already expose (`ReactorModuleCatalog.byId()`, `ManifestsUniverse.domainsById()/unitsById()`), so **no accessor is added to `UnitResource`** — `requireAll` stays the sole production change.

- [ ] **Step 1: Write the failing test**

Create `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/UniverseBuilderTest.java`:

```java
package io.nxmatic.rke2lab.unitrepo.realgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UniverseBuilderTest {

  @Test
  void mergesBothLayersIntoOneUniverse() {
    UniverseBuilder builder = new UniverseBuilder();
    List<UnitResource> universe = builder.universe();
    Map<String, UnitResource> byId = builder.byId();

    // 8 modules + 10 domains + 28 units = 46 unit-resources, one id-space
    assertEquals(46, universe.size(), "8 modules + 10 domains + 28 units");
    assertNotNull(byId.get("seed-master"), "module landmark");
    assertNotNull(byId.get("gitops"), "domain landmark");
    assertNotNull(byId.get("gitops/flux-root"), "unit landmark");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
flox activate -- ./mvnw clean test -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=UniverseBuilderTest
```

Expected: **compile failure** — `UniverseBuilder` does not exist.

- [ ] **Step 3: Implement `UniverseBuilder`**

Create `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/UniverseBuilder.java`:

```java
package io.nxmatic.rke2lab.unitrepo.realgraph;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the coarse ({@link ReactorModuleCatalog}) and fine ({@link ManifestsUniverse}) layers into
 * one {@link UnitResource} universe, attaching the single cross-layer containment edge: the {@code
 * manifests} module gathers all manifest domains via {@code requireAll(module=manifests)}. The
 * combined {@code byId} map (assembled from the layer builders' own id-keyed maps, so no accessor is
 * needed on {@link UnitResource}) lets the resolution test map wiring results back to ids.
 */
final class UniverseBuilder {

  private final List<UnitResource> universe = new ArrayList<>();
  private final Map<String, UnitResource> byId = new LinkedHashMap<>();

  UniverseBuilder() {
    ReactorModuleCatalog modules = new ReactorModuleCatalog();
    ManifestsUniverse manifests = new ManifestsUniverse();

    // cross-layer containment: the manifests module gathers all its domains
    UnitResource manifestsModule = modules.byId().get(ManifestsUniverse.MANIFESTS_MODULE);
    manifestsModule.requireAll(
        ManifestsUniverse.NS_DOMAIN, "(module=" + ManifestsUniverse.MANIFESTS_MODULE + ")");

    // reuse each layer's own id->unit map — UnitResource needs no id() accessor
    addAll(modules.byId());
    addAll(manifests.domainsById());
    addAll(manifests.unitsById());
  }

  private void addAll(Map<String, UnitResource> layer) {
    for (Map.Entry<String, UnitResource> entry : layer.entrySet()) {
      universe.add(entry.getValue());
      byId.put(entry.getKey(), entry.getValue());
    }
  }

  List<UnitResource> universe() {
    return List.copyOf(universe);
  }

  Map<String, UnitResource> byId() {
    return Map.copyOf(byId);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
flox activate -- ./mvnw clean test -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=UniverseBuilderTest
```

Expected: PASS. Confirm via surefire report:

```bash
cat seed-master/target/surefire-reports/*UniverseBuilderTest*.txt | grep -E 'Tests run'
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.

> If `universe.size()` is not 46, recount: 8 modules (Task 3) + 10 domains + 28 units (Task 4). If the unit count differs, the manifests module added/removed a unit — update the assertion to the real count and note it; do not force-fit.

- [ ] **Step 5: Commit**

```bash
git add seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/UniverseBuilder.java \
        seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/UniverseBuilderTest.java
git commit -m "test(unitrepo): UniverseBuilder — merge layers + cross-layer manifests edge"
```

---

## Task 6: `RealGraphResolutionTest` — the proof (happy path)

**Files:**
- Create: `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/RealGraphResolutionTest.java`

Resolves from the `seed-master` module-unit over the merged universe and asserts **kind coverage + landmark edges + the cardinality fan-out anti-cheat** (per the spec — not an exact closure count).

- [ ] **Step 1: Write the failing test**

Create `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/RealGraphResolutionTest.java`:

```java
package io.nxmatic.rke2lab.unitrepo.realgraph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.unitrepo.core.UnitResolver;
import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;

/**
 * The proof: the standalone Felix resolver computes rke2lab's real cross-layer closure (modules +
 * manifest domains + units) in one resolve, replacing the hand-rolled
 * {@code ManifestsUnitDependencyApplier} over real data.
 */
class RealGraphResolutionTest {

  @Test
  void resolvesRealCrossLayerClosureFromSeedMaster() throws ResolutionException {
    UniverseBuilder builder = new UniverseBuilder();
    Map<String, UnitResource> byId = builder.byId();

    UnitResolver resolver = new UnitResolver(builder.universe());
    Map<Resource, List<Wire>> wiring = resolver.resolve(byId.get("seed-master"));

    Set<Resource> closure = wiring.keySet();

    // module layer reached
    assertContains(closure, byId, "seed-master");
    assertContains(closure, byId, "manifests");
    assertContains(closure, byId, "netplan");
    assertContains(closure, byId, "cdk8s-systemd");

    // domain layer reached via the manifests requireAll containment edge
    assertContains(closure, byId, "gitops");
    assertContains(closure, byId, "platform");

    // unit layer reached via each domain's requireAll containment edge, down the real flux chain
    assertContains(closure, byId, "gitops/flux-root");
    assertContains(closure, byId, "gitops/flux-instance");
    assertContains(closure, byId, "gitops/flux-operator");

    // ANTI-CHEAT: the gitops domain's requireAll must have fanned out to >1 unit
    // (proves cardinality:=multiple actually wired every member, not just one)
    UnitResource gitops = byId.get("gitops");
    long gitopsUnitWires =
        wiring.get(gitops).stream()
            .filter(w -> w.getCapability().getNamespace().equals(ManifestsUniverse.NS_UNIT))
            .count();
    assertTrue(gitopsUnitWires > 1, "gitops requireAll fanned out to multiple units, got " + gitopsUnitWires);
  }

  private static void assertContains(
      Set<Resource> closure, Map<String, UnitResource> byId, String id) {
    assertTrue(closure.contains(byId.get(id)), "closure must contain " + id);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails (then passes)**

Because all the production and harness pieces already exist (Tasks 1–5), this test
should compile immediately. Run it:

```bash
flox activate -- ./mvnw clean test -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=RealGraphResolutionTest#resolvesRealCrossLayerClosureFromSeedMaster
```

Expected: **PASS**. If it FAILS on a missing landmark, the diagnosis is real (a
membership attribute or edge is wrong) — fix the harness, not the assertion.

> TDD note: the prior tasks each drove their own unit via a failing test first. This task is the integration assertion; its pieces are already test-driven, so it is expected to pass on first compile. If you want a red-first step, temporarily assert `closure.contains(byId.get("nonexistent"))`, watch it fail, then revert to the real assertions.

- [ ] **Step 3: Confirm via surefire report**

```bash
cat seed-master/target/surefire-reports/*RealGraphResolutionTest*.txt | grep -E 'Tests run'
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 4: Commit**

```bash
git add seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/RealGraphResolutionTest.java
git commit -m "test(unitrepo): prove real cross-layer closure resolves from seed-master"
```

---

## Task 7: `RealGraphResolutionTest` — the unsatisfiable case

**Files:**
- Modify: `seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/RealGraphResolutionTest.java`

Errors-as-values at cross-layer scale: a module-unit requiring a domain nobody provides must throw `ResolutionException`, not silently return an empty closure (same guarantee as `UnitResolverTest`).

- [ ] **Step 1: Write the failing test**

Add this method to `RealGraphResolutionTest` (and add the imports `assertThrows` and `Map.of` usage already present):

```java
  @Test
  void unsatisfiableCrossLayerRequirementThrows() {
    UniverseBuilder builder = new UniverseBuilder();

    // a rogue module-unit that requires a manifest domain nobody provides
    UnitResource rogue =
        new UnitResource("rogue-module")
            .require(ManifestsUniverse.NS_DOMAIN, "(domain=does-not-exist)");

    java.util.List<UnitResource> universe = new java.util.ArrayList<>(builder.universe());
    universe.add(rogue);

    UnitResolver resolver = new UnitResolver(universe);

    org.junit.jupiter.api.Assertions.assertThrows(
        ResolutionException.class,
        () -> resolver.resolve(rogue),
        "an unmet cross-layer requirement is a diagnosable failure, not a silent empty closure");
  }
```

- [ ] **Step 2: Run both tests in the class**

```bash
flox activate -- ./mvnw clean test -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false \
  -Dtest=RealGraphResolutionTest
```

Expected: PASS. Confirm via surefire report:

```bash
cat seed-master/target/surefire-reports/*RealGraphResolutionTest*.txt | grep -E 'Tests run'
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 3: Commit**

```bash
git add seed-master/src/test/java/io/nxmatic/rke2lab/unitrepo/realgraph/RealGraphResolutionTest.java
git commit -m "test(unitrepo): unsatisfiable cross-layer requirement throws ResolutionException"
```

---

## Task 8: Full-reactor verification & final count

**Files:** none (verification only)

- [ ] **Step 1: Build seed-master with all tests through the reactor**

```bash
flox activate -- ./mvnw clean package -pl :seed-master -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Count the new surefire reports (do not trust BUILD SUCCESS alone)**

```bash
for t in ReactorModuleCatalogTest ManifestsUniverseTest UniverseBuilderTest RealGraphResolutionTest; do
  echo "== $t =="
  cat seed-master/target/surefire-reports/*$t*.txt | grep -E 'Tests run'
done
cat unitrepo-core/target/surefire-reports/*UnitResolverTest*.txt | grep -E 'Tests run'
```

Expected:
- `ReactorModuleCatalogTest`: Tests run: 1
- `ManifestsUniverseTest`: Tests run: 1
- `UniverseBuilderTest`: Tests run: 1
- `RealGraphResolutionTest`: Tests run: 2
- `UnitResolverTest`: Tests run: 3

- [ ] **Step 3: Confirm no production surface beyond the one intended change**

```bash
git diff --stat d13961ea -- unitrepo-core/src/main
```

Expected: only `UnitResource.java` changed, and only the `requireAll` verb added. No other production file touched, no other method changed. If anything else appears, investigate before finishing.

- [ ] **Step 4: Final integration commit (if any uncommitted verification artifacts)**

Nothing should be uncommitted at this point. Confirm:

```bash
git status -s
```

Expected: only ` M .flox/env/manifest.lock` (the expected re-smudge noise — leave it unstaged).

---

## Done criteria

- `requireAll` verb in `unitrepo-core` — the sole production change.
- Four new seed-master test-scope classes (`ReactorModuleCatalog`, `ManifestsUniverse`, `UniverseBuilder`, `RealGraphResolutionTest`) + their three companion unit tests.
- `RealGraphResolutionTest` proves: one `resolve(seed-master)` yields a closure spanning modules → domains → units, with the `cardinality:=multiple` fan-out asserted (anti-cheat), and the unsatisfiable case throwing.
- All counts confirmed via surefire reports, not console filtering.
- The hand-rolled `ManifestsUnitDependencyApplier` is **untouched** (its retirement is the migration track's job).
```
