# OSGi Bench (Step 2 / Slice 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a disposable `osgi-bench` reactor module that builds real OSGi bundles with bnd-maven-plugin and proves, against the real Felix framework, (P1) the `Require osgi.extender` contract and (P2) introspectable Metatype `ObjectClassDefinition` retrieval by an unknown client.

**Architecture:** A new throwaway reactor module of disposable classes. bnd-maven-plugin writes bundle manifests (the bnd toolchain experience); a JUnit5 Jupiter extension (`FelixFrameworkExtension`) boots an embedded Felix framework so tests run from VSCode Test Explorer and surefire alike. P1 is pure resolution (a host bundle Provides the extender capability the config bundle Requires); P2 starts the real Felix Metatype runtime and reads an OCD via `MetaTypeService` by PID.

**Tech Stack:** Java 25 (flox), Maven (reactor), bnd-maven-plugin, org.apache.felix.framework 7.0.5, org.apache.felix.metatype, org.osgi.service.metatype 1.4.1, JUnit5 Jupiter.

## Global Constraints

- Toolchain JDK 25 via flox: every Maven command is `flox activate -- ./mvnw …`. (verbatim from CLAUDE.md)
- Inter-module deps resolve through the reactor; build with `-am`. NEVER `mvn install` project artifacts to `~/.m2`. (`dependency:get` of third-party jars into the cache is allowed — it is not a project install.)
- Tests are skipped by default (`.mvn` forces `-DskipTests`); execute with `-DskipTests=false`. A green build with no `Tests run:` line means tests were skipped. (verbatim from CLAUDE.md / `build-verification-gotchas`)
- Build cache can replay stale results and leave `target/classes` empty; for any load-bearing verification pass `-Dmaven.build.cache.skipCache=true`. (`build-verification-gotchas`)
- Tag taxonomy (root pom): `host` / `osgi` / `live` / `spike`; default `surefire.excludedGroups = live | spike`. Bench tests carry `@Tag("osgi")` AND `@Tag("spike")` (throwaway proof) — run them explicitly with `-Posgi` or `-Pall-worlds`, or by not excluding spike. Suffix convention: `*SpikeTest`.
- Artifact id = directory name; group id `io.nxmatic.rke2lab`; `<name>` = relative dir path. Parent is `io.nxmatic.rke2lab:parent:0.1.0-SNAPSHOT`.
- No "superpowers" string in any artifact; specs/plans live under `wip/`.
- Comments document the *why* only; this is a single-developer repo — no compatibility shims.

---

## File structure

- `pom.xml` (root) — add `<module>osgi-bench</module>`.
- `bom/pom.xml` — add version properties + dependencyManagement for felix framework/metatype/configadmin and bnd.
- `osgi-bench/pom.xml` — new module; bnd-maven-plugin; test deps on felix.framework + felix.metatype.
- `osgi-bench/src/main/java/io/nxmatic/rke2lab/osgibench/config/ConfigComponent.java` — disposable "config" class (the Require side).
- `osgi-bench/src/main/java/io/nxmatic/rke2lab/osgibench/host/HostComponent.java` — disposable "host" class (the Provide side).
- `osgi-bench/src/main/bnd/config.bnd` + `host.bnd` (or per-source-set bnd config) — the manifest instructions emitting the capability headers.
- `osgi-bench/src/test/java/io/nxmatic/rke2lab/osgibench/FelixFrameworkExtension.java` — Jupiter extension booting embedded Felix.
- `osgi-bench/src/test/java/io/nxmatic/rke2lab/osgibench/ExtenderContractSpikeTest.java` — P1.
- `osgi-bench/src/test/java/io/nxmatic/rke2lab/osgibench/MetatypeIntrospectionSpikeTest.java` — P2.
- `docs/architecture/integration-atlas.adoc` — config per-subsystem view + monotone proof (graduation at merge).

> **bnd single-jar simplification:** a Maven module produces ONE jar. Two bundles (`config`, `host`) means either two modules or one module that emits both via additional `bnd` artifacts. To keep the bench one module, the plan uses `bnd-maven-plugin`'s multi-`*.bnd`-file support (`<bndfiles>` / the `bnd-process` goal per descriptor) producing classified jars `osgi-bench-<v>-config.jar` and `osgi-bench-<v>-host.jar`. If multi-output proves fiddly in Task 2, the fallback (recorded in that task) is two tiny sub-modules `osgi-bench-config` / `osgi-bench-host` under an `osgi-bench` aggregator.

---

### Task 1: Toolchain — BOM versions + dependency availability

**Files:**
- Modify: `bom/pom.xml` (properties block ends line 41; dependencyManagement ends line 167)

**Interfaces:**
- Produces: BOM-managed coordinates `org.apache.felix:org.apache.felix.framework`, `…metatype`, `…configadmin`, and the bnd plugin version property `bnd.version`, consumed by Task 2's module pom.

- [ ] **Step 1: Verify the third-party jars resolve from Central** (no project install)

Run:
```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/refactor/config-extender
flox activate -- ./mvnw -q dependency:get -Dartifact=org.apache.felix:org.apache.felix.framework:7.0.5
flox activate -- ./mvnw -q dependency:get -Dartifact=org.apache.felix:org.apache.felix.metatype:1.2.4
flox activate -- ./mvnw -q dependency:get -Dartifact=org.apache.felix:org.apache.felix.configadmin:1.9.26
flox activate -- ./mvnw -q dependency:get -Dartifact=biz.aQute.bnd:bnd-maven-plugin:7.0.0
```
Expected: each ends `BUILD SUCCESS`. If a version 404s, pick the nearest existing version from `https://repo.maven.apache.org/maven2/org/apache/felix/…/` and use that throughout the plan (record the substitution here).

- [ ] **Step 2: Add version properties to `bom/pom.xml`**

After line 41 (`<osgi.core.version>8.0.0</osgi.core.version>`), inside `<properties>`:
```xml
    <felix.framework.version>7.0.5</felix.framework.version>
    <felix.metatype.version>1.2.4</felix.metatype.version>
    <felix.configadmin.version>1.9.26</felix.configadmin.version>
    <osgi.metatype.version>1.4.1</osgi.metatype.version>
    <bnd.version>7.0.0</bnd.version>
```

- [ ] **Step 3: Add dependencyManagement entries to `bom/pom.xml`**

Before the closing `</dependencies>` of `<dependencyManagement>` (line 167), after the `osgi.core` block:
```xml
      <dependency>
        <groupId>org.apache.felix</groupId>
        <artifactId>org.apache.felix.framework</artifactId>
        <version>${felix.framework.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.felix</groupId>
        <artifactId>org.apache.felix.metatype</artifactId>
        <version>${felix.metatype.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.felix</groupId>
        <artifactId>org.apache.felix.configadmin</artifactId>
        <version>${felix.configadmin.version}</version>
      </dependency>
      <dependency>
        <groupId>org.osgi</groupId>
        <artifactId>org.osgi.service.metatype</artifactId>
        <version>${osgi.metatype.version}</version>
      </dependency>
```

- [ ] **Step 4: Verify the BOM still builds**

Run: `flox activate -- ./mvnw -q -pl :bom validate`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add bom/pom.xml
git commit -m "build(bom): manage felix framework/metatype/configadmin + bnd versions for the osgi bench"
```

---

### Task 2: `osgi-bench` module + two real bundles built by bnd

**Files:**
- Modify: `pom.xml` (root) — add `<module>osgi-bench</module>` after line 23 (`<module>seed-master</module>` is last; add before/after consistently — append as the final module).
- Create: `osgi-bench/pom.xml`
- Create: `osgi-bench/src/main/java/io/nxmatic/rke2lab/osgibench/config/ConfigComponent.java`
- Create: `osgi-bench/src/main/java/io/nxmatic/rke2lab/osgibench/host/HostComponent.java`
- Create: `osgi-bench/src/main/bnd/config.bnd`
- Create: `osgi-bench/src/main/bnd/host.bnd`

**Interfaces:**
- Produces: two bundle jars in `osgi-bench/target/` — one with `Require-Capability: osgi.extender` (filters for `osgi.metatype` and `osgi.component`), one with matching `Provide-Capability: osgi.extender`. Their file paths are consumed by Task 3's `FelixFrameworkExtension`.

- [ ] **Step 1: Register the module in the root reactor**

In `pom.xml`, in `<modules>`, append after `<module>seed-master</module>`:
```xml
    <module>osgi-bench</module>
```

- [ ] **Step 2: Write the two disposable classes**

`osgi-bench/src/main/java/io/nxmatic/rke2lab/osgibench/config/ConfigComponent.java`:
```java
package io.nxmatic.rke2lab.osgibench.config;

/** Disposable bench stand-in for a config-bearing bundle. Its only role is to carry the
 *  Require-Capability header bnd emits for this package's bundle. */
public final class ConfigComponent {
  private ConfigComponent() {}
}
```

`osgi-bench/src/main/java/io/nxmatic/rke2lab/osgibench/host/HostComponent.java`:
```java
package io.nxmatic.rke2lab.osgibench.host;

/** Disposable bench stand-in for the host bundle that runs the config-delivery extenders.
 *  Its only role is to carry the Provide-Capability header bnd emits for this package's bundle. */
public final class HostComponent {
  private HostComponent() {}
}
```

- [ ] **Step 3: Write the two bnd descriptors**

`osgi-bench/src/main/bnd/config.bnd`:
```
Bundle-SymbolicName: io.nxmatic.rke2lab.osgibench.config
Export-Package: io.nxmatic.rke2lab.osgibench.config
Require-Capability: \
  osgi.extender;filter:="(&(osgi.extender=osgi.metatype)(version>=1.4))", \
  osgi.extender;filter:="(&(osgi.extender=osgi.component)(version>=1.5))"
-noimportjava: true
```

`osgi-bench/src/main/bnd/host.bnd`:
```
Bundle-SymbolicName: io.nxmatic.rke2lab.osgibench.host
Export-Package: io.nxmatic.rke2lab.osgibench.host
Provide-Capability: \
  osgi.extender;osgi.extender=osgi.metatype;version:Version=1.4, \
  osgi.extender;osgi.extender=osgi.component;version:Version=1.5
-noimportjava: true
```

- [ ] **Step 4: Write `osgi-bench/pom.xml` (bnd emits one bundle per descriptor)**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.nxmatic.rke2lab</groupId>
    <artifactId>parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>osgi-bench</artifactId>
  <name>osgi-bench</name>

  <dependencies>
    <dependency>
      <groupId>org.osgi</groupId>
      <artifactId>osgi.core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.osgi</groupId>
      <artifactId>org.osgi.service.metatype</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.felix</groupId>
      <artifactId>org.apache.felix.framework</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.felix</groupId>
      <artifactId>org.apache.felix.metatype</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>biz.aQute.bnd</groupId>
        <artifactId>bnd-maven-plugin</artifactId>
        <version>${bnd.version}</version>
        <executions>
          <execution>
            <id>config-bundle</id>
            <goals><goal>jar</goal></goals>
            <configuration>
              <bndfile>src/main/bnd/config.bnd</bndfile>
              <classifier>config</classifier>
            </configuration>
          </execution>
          <execution>
            <id>host-bundle</id>
            <goals><goal>jar</goal></goals>
            <configuration>
              <bndfile>src/main/bnd/host.bnd</bndfile>
              <classifier>host</classifier>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

> If `bnd-maven-plugin`'s `jar` goal does not accept per-execution `<classifier>`/`<bndfile>` in 7.0.0, FALL BACK to two leaf modules `osgi-bench-config` / `osgi-bench-host` under an `osgi-bench` `<packaging>pom</packaging>` aggregator, each with its own `bnd.bnd`. Record which path was taken here.

- [ ] **Step 5: Build the module with the cache disabled**

Run:
```bash
flox activate -- ./mvnw clean package -pl :osgi-bench -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false
```
Expected: `BUILD SUCCESS`; `osgi-bench/target/` contains a `*-config.jar` and a `*-host.jar`.

- [ ] **Step 6: Verify the manifests carry the real capability headers (the toolchain gate)**

Run:
```bash
unzip -p osgi-bench/target/osgi-bench-*-config.jar META-INF/MANIFEST.MF | tr -d '\r' | grep -A1 'Require-Capability'
unzip -p osgi-bench/target/osgi-bench-*-host.jar  META-INF/MANIFEST.MF | tr -d '\r' | grep -A1 'Provide-Capability'
```
Expected: the config manifest shows `Require-Capability: osgi.extender;filter:=…osgi.metatype…` and `…osgi.component…`; the host manifest shows `Provide-Capability: osgi.extender;osgi.extender=osgi.metatype…` and `…osgi.component…`. This proves bnd built real bundles — the founding bndtools experience.

- [ ] **Step 7: Commit**

```bash
git add pom.xml osgi-bench/
git commit -m "feat(osgi-bench): bnd-built config + host bundles carrying the osgi.extender contract"
```

---

### Task 3: `FelixFrameworkExtension` + P1 (the extender contract on the real engine)

**Files:**
- Create: `osgi-bench/src/test/java/io/nxmatic/rke2lab/osgibench/FelixFrameworkExtension.java`
- Create: `osgi-bench/src/test/java/io/nxmatic/rke2lab/osgibench/ExtenderContractSpikeTest.java`

**Interfaces:**
- Consumes: the `*-config.jar` / `*-host.jar` in `osgi-bench/target/` (Task 2).
- Produces: `FelixFrameworkExtension` exposing `BundleContext context()` and `Bundle install(String classifier)` — consumed by Task 4's P2 test.

- [ ] **Step 1: Write the Jupiter extension (boots embedded Felix)**

`FelixFrameworkExtension.java`:
```java
package io.nxmatic.rke2lab.osgibench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Boots a real embedded Felix framework once per test class so bench tests observe the actual OSGi
 * resolution/runtime — not the hand-rolled resolver algorithm. A plain Jupiter extension (sibling of
 * {@code GrpcChannelNoiseCapture}) so the tests stay ordinary JUnit5 and launch from VSCode Test
 * Explorer as well as surefire.
 */
public final class FelixFrameworkExtension implements BeforeAllCallback, AfterAllCallback {

  private Framework framework;

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    Path storage = Files.createTempDirectory("osgi-bench-felix");
    FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
    framework =
        factory.newFramework(
            Map.of(
                Constants.FRAMEWORK_STORAGE, storage.toString(),
                Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT));
    framework.init();
    framework.start();
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    if (framework != null) {
      framework.stop();
      framework.waitForStop(5000);
    }
  }

  public BundleContext context() {
    return framework.getBundleContext();
  }

  /** Install the bench bundle whose file name ends with {@code -<classifier>.jar} from target/. */
  public Bundle install(String classifier) throws Exception {
    Path target = Path.of("target");
    Path jar;
    try (var stream = Files.list(target)) {
      jar =
          stream
              .filter(p -> p.getFileName().toString().endsWith("-" + classifier + ".jar"))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("no -" + classifier + ".jar in target/"));
    }
    return context().installBundle(jar.toUri().toString());
  }

  public boolean resolve(List<Bundle> bundles) {
    return framework.adapt(org.osgi.framework.wiring.FrameworkWiring.class).resolveBundles(bundles);
  }
}
```

- [ ] **Step 2: Write the P1 failing test**

`ExtenderContractSpikeTest.java`:
```java
package io.nxmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;

/**
 * P1 — the real Felix framework resolves the config bundle's {@code Require osgi.extender} against the
 * host bundle's {@code Provide}, and fails to resolve when the host is absent (loud, not a silent
 * empty closure). The real-engine successor to the hand-rolled {@code ConfigExtenderResolutionSpike}.
 */
@Tag("osgi")
@Tag("spike")
class ExtenderContractSpikeTest {

  @RegisterExtension static final FelixFrameworkExtension felix = new FelixFrameworkExtension();

  @Test
  void configResolvesWhenHostProvidesTheExtenders() throws Exception {
    Bundle host = felix.install("host");
    Bundle config = felix.install("config");

    boolean resolved = felix.resolve(List.of(host, config));

    assertTrue(resolved, "framework resolved the bundle set");
    assertTrue(
        config.getState() >= Bundle.RESOLVED && config.getState() != Bundle.INSTALLED,
        "config bundle wired to the extender-providing host");
  }

  @Test
  void configStaysUnresolvedWhenHostAbsent() throws Exception {
    Bundle config = felix.install("config");

    boolean resolved = felix.resolve(List.of(config));

    assertFalse(resolved, "no provider for osgi.extender — resolution refuses, loudly");
  }
}
```

> Note: the two tests share one class-scoped framework. The second installs only `config`; because the first test's `host` may already be present in the shared framework, run them in a fresh framework per test if they interfere. If interference appears (the absent-host test resolves because host lingers), change `FelixFrameworkExtension` to `BeforeEachCallback`/`AfterEachCallback` (record the change here). Verified in Step 4.

- [ ] **Step 3: Run P1, expecting failure first (no bundles built into a clean module run)**

Run:
```bash
flox activate -- ./mvnw test -pl :osgi-bench -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false -Posgi \
  -Dtest=ExtenderContractSpikeTest
```
Expected initially: FAIL if the extension or bundle wiring is wrong (e.g. classifier mismatch, missing provider semantics). Read the failure; it is the real engine's verdict.

- [ ] **Step 4: Make P1 pass; confirm both assertions**

Iterate on the bnd filters / extension until:
Expected: `Tests run: 2, Failures: 0` in `osgi-bench/target/surefire-reports/`. Confirm the count by reading the report file, not just `BUILD SUCCESS`.

- [ ] **Step 5: Confirm VSCode-runnability (manual, one-time)**

Open `ExtenderContractSpikeTest` in VSCode; the Test Explorer gutter shows run icons; clicking runs the two tests green. (No command; this is the requirement's acceptance check.)

- [ ] **Step 6: Commit**

```bash
git add osgi-bench/src/test/
git commit -m "test(osgi-bench): P1 — real Felix resolves the osgi.extender contract, refuses when host absent"
```

---

### Task 4: P2 — introspectable Metatype schema via `MetaTypeService`

**Files:**
- Create: `osgi-bench/src/main/java/io/nxmatic/rke2lab/osgibench/schema/SchemaComponent.java`
- Create: `osgi-bench/src/main/resources/OSGI-INF/metatype/io.nxmatic.rke2lab.osgibench.schema.xml`
- Create: `osgi-bench/src/main/bnd/schema.bnd`
- Modify: `osgi-bench/pom.xml` — add a third bnd execution `schema-bundle` (classifier `schema`).
- Create: `osgi-bench/src/test/java/io/nxmatic/rke2lab/osgibench/MetatypeIntrospectionSpikeTest.java`

**Interfaces:**
- Consumes: `FelixFrameworkExtension` (Task 3); the felix.metatype runtime bundle on the test classpath (Task 1/2 BOM).
- Produces: nothing downstream (terminal proof).

- [ ] **Step 1: Write the OCD descriptor (the schema as data, one domain modelled on INCUS keys)**

`osgi-bench/src/main/resources/OSGI-INF/metatype/io.nxmatic.rke2lab.osgibench.schema.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<metatype:MetaData xmlns:metatype="http://www.osgi.org/xmlns/metatype/v1.4.0">
  <OCD id="io.nxmatic.rke2lab.osgibench.incus" name="Incus domain config">
    <AD id="configDir" type="String" cardinality="0" required="true"
        name="configDir" description="Incus config directory (required)"/>
    <AD id="project" type="String" cardinality="0" required="false"
        name="project" description="Incus project (optional)"/>
  </OCD>
  <Designate pid="io.nxmatic.rke2lab.osgibench.incus">
    <Object ocdref="io.nxmatic.rke2lab.osgibench.incus"/>
  </Designate>
</metatype:MetaData>
```

- [ ] **Step 2: Write the bundle's marker class + bnd descriptor (include the metatype resource)**

`SchemaComponent.java`:
```java
package io.nxmatic.rke2lab.osgibench.schema;

/** Disposable bench bundle that ships a Metatype OCD descriptor so a client can introspect its
 *  config schema (keys/type/required) by PID, without knowing this provider. */
public final class SchemaComponent {
  private SchemaComponent() {}
}
```

`osgi-bench/src/main/bnd/schema.bnd`:
```
Bundle-SymbolicName: io.nxmatic.rke2lab.osgibench.schema
Export-Package: io.nxmatic.rke2lab.osgibench.schema
Require-Capability: osgi.extender;filter:="(&(osgi.extender=osgi.metatype)(version>=1.4))"
-includeresource: OSGI-INF/metatype/=src/main/resources/OSGI-INF/metatype/
-noimportjava: true
```

- [ ] **Step 3: Add the `schema-bundle` execution to `osgi-bench/pom.xml`**

Inside the bnd-maven-plugin `<executions>`, after `host-bundle`:
```xml
          <execution>
            <id>schema-bundle</id>
            <goals><goal>jar</goal></goals>
            <configuration>
              <bndfile>src/main/bnd/schema.bnd</bndfile>
              <classifier>schema</classifier>
            </configuration>
          </execution>
```

- [ ] **Step 4: Write the P2 failing test**

`MetatypeIntrospectionSpikeTest.java`:
```java
package io.nxmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.service.metatype.AttributeDefinition;
import org.osgi.service.metatype.MetaTypeService;
import org.osgi.service.metatype.ObjectClassDefinition;

/**
 * P2 — an unknown client retrieves the schema (keys/type/required) of a domain by PID via
 * {@code MetaTypeService}, without knowing the provider. Proves the schema is introspectable and
 * structured, the owner's requirement.
 */
@Tag("osgi")
@Tag("spike")
class MetatypeIntrospectionSpikeTest {

  @RegisterExtension static final FelixFrameworkExtension felix = new FelixFrameworkExtension();

  @Test
  void unknownClientReadsTheSchemaByPid() throws Exception {
    // the felix metatype runtime bundle must be installed + started to register MetaTypeService
    Bundle metatypeRuntime = installFelixMetatypeRuntime();
    metatypeRuntime.start();
    Bundle schema = felix.install("schema");
    schema.start();

    ServiceReference<MetaTypeService> ref =
        felix.context().getServiceReference(MetaTypeService.class);
    assertNotNull(ref, "MetaTypeService registered by the felix metatype runtime");
    MetaTypeService mts = felix.context().getService(ref);

    ObjectClassDefinition ocd =
        mts.getMetaTypeInformation(schema)
            .getObjectClassDefinition("io.nxmatic.rke2lab.osgibench.incus", null);
    assertNotNull(ocd, "OCD retrieved by PID");

    AttributeDefinition[] required = ocd.getAttributeDefinitions(ObjectClassDefinition.REQUIRED);
    AttributeDefinition[] optional = ocd.getAttributeDefinitions(ObjectClassDefinition.OPTIONAL);

    assertTrue(
        Arrays.stream(required).anyMatch(a -> a.getID().equals("configDir")),
        "configDir is discoverable as required");
    assertTrue(
        Arrays.stream(optional).anyMatch(a -> a.getID().equals("project")),
        "project is discoverable as optional");
    assertEquals(
        AttributeDefinition.STRING,
        Arrays.stream(required).filter(a -> a.getID().equals("configDir")).findFirst().get().getType(),
        "configDir typed STRING");
  }

  /** Locate the felix.metatype jar on the test classpath and install it into the framework. */
  private Bundle installFelixMetatypeRuntime() throws Exception {
    String cp = System.getProperty("java.class.path");
    String jar =
        Arrays.stream(cp.split(java.io.File.pathSeparator))
            .filter(p -> p.contains("org.apache.felix.metatype"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("felix.metatype not on test classpath"));
    return felix.context().installBundle("file:" + jar);
  }
}
```

- [ ] **Step 5: Run P2, expect failure first**

Run:
```bash
flox activate -- ./mvnw test -pl :osgi-bench -am \
  -Dmaven.build.cache.skipCache=true -DskipTests=false -Posgi \
  -Dtest=MetatypeIntrospectionSpikeTest
```
Expected initially: FAIL (e.g. metatype runtime not resolving — it itself `Require`s nothing exotic, but the schema bundle's `Require osgi.extender=osgi.metatype` must be satisfied by the running metatype runtime, which `Provide`s that extender capability). Read the verdict.

- [ ] **Step 6: Make P2 pass**

Iterate (the metatype runtime provides `osgi.extender; osgi.extender=osgi.metatype`, satisfying the schema bundle's requirement; both must be started). 
Expected: `Tests run: 1, Failures: 0` — confirm via the surefire report file.

- [ ] **Step 7: Commit**

```bash
git add osgi-bench/
git commit -m "test(osgi-bench): P2 — unknown client introspects an OCD schema by PID via MetaTypeService"
```

---

### Task 5: Atlas — config per-subsystem view + monotone additivity proof

**Files:**
- Modify: `docs/architecture/integration-atlas.adoc` (per-subsystem index ~line 84-89; add a new `[[config-view]]` section after the doctor view, before "Related documentation" ~line 444)

**Interfaces:**
- Consumes: nothing in code; documents the proven bench.
- Produces: the durable atlas's second per-subsystem view (graduation of this slice).

- [ ] **Step 1: Add the config view to the per-subsystem index**

After line 87 (`* <<doctor-view,Doctor subsystem>> …`) add:
```asciidoc
* <<config-view,Config subsystem>> — the bundle/host contract (Require osgi.extender) and the
  introspectable Metatype schema, proven on the real Felix engine by the OSGi bench (Step 2 / slice 2).
```

- [ ] **Step 2: Add the config per-subsystem section with the monotone before/after proof**

Before `== Related documentation` (line 444), insert a `[[config-view]] == Config subsystem` section containing: (a) a one-paragraph model inventory row (extender contract = proven on real engine; Metatype schema = NEW box); (b) a `[mermaid]` BEFORE figure (config island: `InfraDomain` enum loop, no resolver edge) and an AFTER figure adding two green boxes — `Require osgi.extender` (resolution half) and the Metatype OCD (schema, introspectable) — with NO existing box erased; (c) a verdict stating monotone holds, that only the *schema/resolution* half is proven and the *value/SectionReader* half stays stage 3.

Full AsciiDoc to insert:
```asciidoc
[[config-view]]
== Config subsystem — per-subsystem view

Step 2's second subsystem to adopt the ritual. The OSGi bench proved, on the real Felix framework, the
*schema/resolution* half of the crossing contract: the `Require osgi.extender` contract (P1) and an
introspectable Metatype `ObjectClassDefinition` retrieved by PID (P2). The *value/`SectionReader`* half
stays stage 3.

=== Diagram E — config seam: BEFORE (the enum island)

[mermaid]
....
flowchart TB
  enumb["InfraDomain enum loop"]
  regb["InfraConfigRegistry aggregates fragments"]
  rke2b["Rke2labConfig pure record"]
  enumb -->|contribute| regb
  regb --> rke2b
  classDef built fill:#eef,stroke:#88a,stroke-width:1px;
  class enumb,regb,rke2b built;
....

=== Diagram F — AFTER (+ extender contract + Metatype schema, monotone)

[mermaid]
....
flowchart TB
  enuma["InfraDomain enum loop unchanged"]
  rega["InfraConfigRegistry unchanged"]
  rke2a["Rke2labConfig pure record unchanged"]
  needs["config bundle Require osgi.extender — PROVEN on real Felix"]
  ocd["Metatype ObjectClassDefinition introspectable by PID — NEW"]
  host["host bundle Provide osgi.extender"]
  enuma -->|contribute| rega
  rega --> rke2a
  needs -->|resolved by real framework| host
  ocd -.->|describes keys/type/required of| rega
  classDef built fill:#eef,stroke:#88a,stroke-width:1px;
  classDef neu fill:#dfd,stroke:#2a2,stroke-width:3px;
  class enuma,rega,rke2a built;
  class needs,ocd,host neu;
....

=== Verdict

Monotone: no built box erased or rewired; three green additions (the Require/Provide extender pair and
the Metatype OCD). Honest scope: only the schema/resolution half is proven here — the value channel
(`SectionReader`, inverting `Rke2labConfig → ConfigLoader`) is stage 3, and Config Admin delivery / DS
activation are stages 3–4, gated. The proof is on the REAL engine (embedded Felix), not the resolver
algorithm alone.
```

- [ ] **Step 3: Verify the AsciiDoc renders (no broken anchors)**

Run: `grep -n 'config-view' docs/architecture/integration-atlas.adoc`
Expected: two hits — the index link and the section anchor.

- [ ] **Step 4: Commit**

```bash
git add docs/architecture/integration-atlas.adoc
git commit -m "docs(atlas): config subsystem view — extender contract + Metatype schema, monotone proof"
```

---

## Self-review

**Spec coverage:**
- §1 P1 extender contract → Task 2 (bundles) + Task 3 (real-engine proof). ✓
- §1 P2 Metatype introspection → Task 4. ✓
- §2 disposable sub-module, zero duplication → Task 2 (only stand-in classes). ✓
- §3 VSCode runner = JUnit5 + Jupiter extension → Task 3 (`FelixFrameworkExtension`, Step 5 acceptance). ✓
- §3 bnd builder kept → Task 2 (bnd-maven-plugin). ✓
- §4 deps to fetch + BOM anchoring → Task 1. ✓
- §4 atlas ritual (config view + monotone) → Task 5. ✓
- §5 deferred items (rename, git-mv, SectionReader, Config Admin/DS) → NOT tasked, correctly out of scope. ✓
- §6 verification checklist → Task 2 Step 6 (manifest), Task 3 Step 4 (P1 count), Task 4 Step 6 (P2 count), Task 3 Step 5 (VSCode), Task 5 (atlas). ✓

**Placeholder scan:** the two `>`-quoted fallbacks (bnd multi-output; per-test framework) are explicit decision points with a recorded-outcome instruction, not TODOs — acceptable. No "TBD"/"add error handling"/"similar to Task N".

**Type consistency:** `FelixFrameworkExtension` exposes `context()`, `install(String)`, `resolve(List<Bundle>)` — used identically in Tasks 3 and 4. Classifiers `config`/`host`/`schema` consistent between bnd executions (Task 2/4) and `install(...)` calls (Task 3/4). PID `io.nxmatic.rke2lab.osgibench.incus` matches between the OCD XML and the P2 test.

**One open risk flagged for execution, not a plan gap:** bnd-maven-plugin 7.0.0's exact multi-`bndfile` execution syntax and the embedded-metatype-runtime install path are verified empirically in Tasks 2 and 4 (each has a fail-first step and a recorded fallback). This is inherent to a toolchain-learning slice.
