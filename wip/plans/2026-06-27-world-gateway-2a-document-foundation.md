# World Gateway 2A — Document Foundation + Readiness Verdict — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the neutral `Document` seam contract and cross the readiness verdict through it as a structured Document, removing every `doctor.records` parse/field from `ControlplanePolicy` (the boot-crash trigger).

**Architecture:** A new `osgi/gateway/gateway-port` seam module (`type=seam`) carries an immutable `record Document(domain, coordinate, JsonNode payload)` and the `ReadinessAuthority` verb (`assess(Document) → Document`). The OSGi-side authority impl is a `doctor-core` `@Component` (the doctor owns `Severity` + the intrinsic severities). `ControlplanePolicy` drops its `Map<String,Severity>`/`Map<String,Symptom>` fields and `from()`'s `Severity.parse`/`Symptom.parse`, holding the readiness-override + preview-simulate as raw config strings. `SystemdAdapterStage` builds a checkpoint Document, awaits `ReadinessAuthority`, and reads the verdict's `action` field instead of `== Severity.CRITICAL`.

**Tech Stack:** Java 25 (flox), Maven (reactor + the separately-installed staging extension), bnd-maven-plugin, OSGi Declarative Services (Felix SCR), Jackson `JsonNode` (host-flat via `system.packages.extra`), JUnit5 Jupiter.

## Global Constraints

- Toolchain JDK 25 via flox: every Maven command is `flox activate -- ./mvnw …`. (verbatim from CLAUDE.md)
- Inter-module deps resolve through the reactor; build with `-am` (also-make). NEVER `mvn install` project artifacts to `~/.m2` — a bare `-pl` resolves siblings from stale jars and fails. (verbatim from CLAUDE.md)
- Tests are skipped by default (`.mvn` forces `-DskipTests`); execute with `-DskipTests=false`. A green build with no `Tests run:` line means tests were skipped, not passed. (verbatim from CLAUDE.md)
- Build cache: pass `-Dmaven.build.cache.skipCache=true` for any load-bearing verification (SKIP, keeps the staging extension active — NOT `enabled=false`). (`maven-build-cache-and-staging-verify`)
- A new module that adds an enum constant or annotation read by the staging extension requires the extension be rebuilt first: `flox activate -- ./mvnw -f maven-embed-staging-ext/pom.xml install -DskipTests -Dmaven.build.cache.skipCache=true`. (2A adds no such constant, but a full-reactor verify still loads the installed extension.)
- New module artifactId = directory name; groupId `io.nxmatic.rke2lab`; `<name>` = relative dir path; parent is `io.nxmatic.rke2lab:bundle-parent:0.0.0-SNAPSHOT` for a bundle, `build-parent:0.0.0` for a `pom`-packaging aggregator. (verbatim from existing poms)
- Design-of-record: `docs/architecture/osgi/world-gateway-2a-document-foundation-spec.adoc` (this increment) and `docs/architecture/osgi/world-gateway-spec.adoc` (the parent design).

### Repo patterns every task MUST honor (the reviewer checks these)

- **Single source of truth for identifiers:** the coordinates (`"readiness-checkpoint"`, `"readiness-verdict"`) and the payload field names (`"action"`, `"reason"`, `"scenarioId"`, `"failed"`, `"override"`) and the verdict values (`"stop"`, `"continue-degraded"`) are defined ONCE in an `GatewayCatalog` constants class — NEVER hardcoded magic strings at call sites. (CLAUDE.md § Single-source-of-truth; the `clusterApi`-bug discipline.)
- **Immutability by default:** `Document` is a `record`; any new value type is a record. (CLAUDE.md § Immutability)
- **Instance-passing discipline:** no `public static` behaviour helpers on exported types (the `INSTANCE_DISCIPLINE` gate fails the build); factories (`of`, `from*`, `parse`, `builder`, `create`, `defaults`) are the only endorsed static surface. Pass instances through the call graph. (CLAUDE.md § Instance-passing; the gate is real.)
- **Seam module conventions:** a `-port` carries `Bundle-SymbolicName` + `Export-Package` (the one package) + `Provide-Capability: io.nxmatic.rke2lab.embed; type=seam` + `-noimportjava: true` in `bnd.bnd`; a `package-info.java` with `@org.osgi.annotation.versioning.Version`. Exported types must be named in a `docs/` spec or the `SPEC_COVERAGE` gate fails — the 2A spec already names `Document` and `ReadinessAuthority`. (existing `cluster-port`; `staging-gates-governance-spec.adoc`)
- **No dead code / no shims:** when a path is superseded, delete it entirely and update all call sites in the SAME change; no `@Deprecated`, no compatibility branches. (CLAUDE.md § Code style)
- **Comments document the *why* only**, not the *what*; no task-number references in code. (CLAUDE.md)
- **Lazy/complete construction:** never an object with `null`/partial required state. (CLAUDE.md § No instances with incomplete state)

---

## File structure

**Task 1 — the `gateway` domain + `gateway-port` seam + `Document` + `GatewayCatalog`:**
- Create: `osgi/gateway/pom.xml` (packaging `pom`, one module `gateway-port`).
- Modify: `osgi/pom.xml` (add `<module>gateway</module>`).
- Create: `osgi/gateway/gateway-port/pom.xml`, `osgi/gateway/gateway-port/bnd.bnd`.
- Create: `osgi/gateway/gateway-port/src/main/java/io/nxmatic/rke2lab/gateway/port/Document.java`.
- Create: `…/gateway/port/GatewayCatalog.java` (the coordinate + field-name + verdict-value constants).
- Create: `…/gateway/port/package-info.java`.
- Test: `osgi/gateway/gateway-port/src/test/java/io/nxmatic/rke2lab/gateway/port/DocumentTest.java`.

**Task 2 — `ReadinessAuthority` seam verb + the doctor-core `@Component` impl:**
- Create: `osgi/gateway/gateway-port/src/main/java/io/nxmatic/rke2lab/gateway/port/ReadinessAuthority.java`.
- Create: `osgi/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/internal/DefaultReadinessAuthority.java` (`@Component` implementing `ReadinessAuthority`).
- Modify: `osgi/doctor/doctor-core/pom.xml` (add `gateway-port` dependency).
- Test: `osgi/doctor/doctor-core/src/test/java/io/nxmatic/rke2lab/doctor/ReadinessAuthorityTest.java`.

**Task 3 — `ControlplanePolicy` + `from()` doctor-free (raw config):**
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/policy/ControlplanePolicy.java` (drop `Severity`/`Symptom` imports, fields, parses; hold raw `Map<String,String>`).
- Test: `exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/policy/ControlplanePolicyRawConfigTest.java`.

**Task 4 — `SystemdAdapterStage` reads the verdict via `ReadinessAuthority` + pipeline wiring:**
- Modify: `exec/seed-master/.../pipeline/stages/SystemdAdapterStage.java` (build checkpoint Document, read `action`, drop `Severity`).
- Modify: `exec/seed-master/.../pipeline/BootstrapPipeline.java` (await `ReadinessAuthority`, store on state, inject into the stage).
- Modify: `exec/seed-master/pom.xml` (add `gateway-port` dependency).
- Test: `exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterVerdictTest.java`.

---

## Task 1: The `gateway-port` seam, the `Document` record, the `GatewayCatalog`

**Files:**
- Create: `osgi/gateway/pom.xml`
- Modify: `osgi/pom.xml` (add `<module>gateway</module>` in the `<modules>` list, alphabetically near `cluster`)
- Create: `osgi/gateway/gateway-port/pom.xml`
- Create: `osgi/gateway/gateway-port/bnd.bnd`
- Create: `osgi/gateway/gateway-port/src/main/java/io/nxmatic/rke2lab/gateway/port/Document.java`
- Create: `osgi/gateway/gateway-port/src/main/java/io/nxmatic/rke2lab/gateway/port/GatewayCatalog.java`
- Create: `osgi/gateway/gateway-port/src/main/java/io/nxmatic/rke2lab/gateway/port/package-info.java`
- Test: `osgi/gateway/gateway-port/src/test/java/io/nxmatic/rke2lab/gateway/port/DocumentTest.java`

**Interfaces:**
- Consumes: nothing (first task). Jackson `com.fasterxml.jackson.databind.JsonNode` (host-flat by construction — `manifests-core` imports it and the boot mirrors model-bundle imports into `system.packages.extra`).
- Produces:
  - `record Document(String domain, String coordinate, com.fasterxml.jackson.databind.JsonNode payload)` in package `io.nxmatic.rke2lab.gateway.port`.
  - `final class GatewayCatalog` with `public static final String` constants: `READINESS_CHECKPOINT = "readiness-checkpoint"`, `READINESS_VERDICT = "readiness-verdict"`, `DOMAIN_DOCTOR = "doctor"`, `FIELD_SCENARIO_ID = "scenarioId"`, `FIELD_FAILED = "failed"`, `FIELD_OVERRIDE = "override"`, `FIELD_ACTION = "action"`, `FIELD_REASON = "reason"`, `ACTION_STOP = "stop"`, `ACTION_CONTINUE_DEGRADED = "continue-degraded"`. Private constructor (utility constants class).

- [ ] **Step 1: Create the `gateway` aggregator pom**

`osgi/gateway/pom.xml` (mirror `osgi/cluster/pom.xml` exactly — `build-parent` parent, `pom` packaging):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.nxmatic.rke2lab</groupId>
    <artifactId>build-parent</artifactId>
    <version>0.0.0</version>
    <relativePath>../../build-parent/pom.xml</relativePath>
  </parent>

  <artifactId>gateway</artifactId>
  <version>0.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>osgi/gateway</name>
  <description>The host↔OSGi gateway seam: the neutral Document envelope every world-gateway crossing
    carries, and the gateway verbs (ReadinessAuthority) the host calls. type=seam packages, system-
    exported, one shared copy across the boundary. See docs/architecture/osgi/world-gateway-spec.adoc.</description>

  <modules>
    <module>gateway-port</module>
  </modules>
</project>
```

- [ ] **Step 2: Register the module in `osgi/pom.xml`**

In `osgi/pom.xml`, add `<module>gateway</module>` to the `<modules>` list (place it adjacent to `<module>cluster</module>`).

Run: `flox activate -- ./mvnw -q -pl :gateway validate -Dmaven.build.cache.skipCache=true`
Expected: `BUILD SUCCESS` (the aggregator resolves; gateway-port not built yet — that's the next steps).

- [ ] **Step 3: Create the `gateway-port` bundle pom**

`osgi/gateway/gateway-port/pom.xml` (mirror `cluster-port`'s pom — `bundle-parent`, the bnd plugin; the only dependency is Jackson databind, `provided` because it is host-flat at runtime):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.nxmatic.rke2lab</groupId>
    <artifactId>bundle-parent</artifactId>
    <version>0.0.0-SNAPSHOT</version>
    <relativePath>../../bundle-parent/pom.xml</relativePath>
  </parent>

  <artifactId>gateway-port</artifactId>
  <name>osgi/gateway/gateway-port</name>
  <description>The world-gateway seam: the neutral Document envelope (domain, coordinate, JsonNode
    payload) every host↔OSGi crossing carries, the GatewayCatalog of coordinate + field names, and
    the ReadinessAuthority verb the host calls to turn a checkpoint outcome into a provisioning
    verdict. type=seam: system-exported, one shared copy across the boundary, like every -port.</description>

  <dependencies>
    <!-- JsonNode is the Document payload. databind is host-flat at runtime (manifests-core imports
         it; the boot mirrors model-bundle imports into system.packages.extra), so the bundle compiles
         against it but does not embed it. -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
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
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: Create the `bnd.bnd`**

`osgi/gateway/gateway-port/bnd.bnd` (mirror `cluster-port/bnd.bnd`'s seam shape):

```
Bundle-SymbolicName: io.nxmatic.rke2lab.gateway.port
Export-Package: io.nxmatic.rke2lab.gateway.port
# The world-gateway seam: the flat host and the bundles share ONE copy of the Document envelope and
# the gateway verbs, so the system bundle is the SOLE exporter of this package (one exporter = one
# class, no split). type=seam declares that boot face — system-exported, never installed as a bundle,
# and the REALM_BOUNDARY/leak guard never flags it. A discovery marker, not a resolution capability.
# See docs/architecture/osgi/world-gateway-spec.adoc.
Provide-Capability: io.nxmatic.rke2lab.embed; type=seam
-noimportjava: true
```

- [ ] **Step 5: Write the failing test for `Document` + `GatewayCatalog`**

`osgi/gateway/gateway-port/src/test/java/io/nxmatic/rke2lab/gateway/port/DocumentTest.java`:

```java
package io.nxmatic.rke2lab.gateway.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class DocumentTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void carriesDomainCoordinateAndStructuredPayload() {
    final ObjectNode payload = MAPPER.createObjectNode();
    payload.put(GatewayCatalog.FIELD_ACTION, GatewayCatalog.ACTION_STOP);
    final Document doc =
        new Document(GatewayCatalog.DOMAIN_DOCTOR, GatewayCatalog.READINESS_VERDICT, payload);

    assertEquals(GatewayCatalog.DOMAIN_DOCTOR, doc.domain());
    assertEquals(GatewayCatalog.READINESS_VERDICT, doc.coordinate());
    assertEquals(
        GatewayCatalog.ACTION_STOP, doc.payload().get(GatewayCatalog.FIELD_ACTION).asText());
  }

  @Test
  void catalogConstantsAreTheCanonicalStrings() {
    // The single source of truth — call sites must reference these, never literals.
    assertEquals("readiness-checkpoint", GatewayCatalog.READINESS_CHECKPOINT);
    assertEquals("readiness-verdict", GatewayCatalog.READINESS_VERDICT);
    assertEquals("stop", GatewayCatalog.ACTION_STOP);
    assertEquals("continue-degraded", GatewayCatalog.ACTION_CONTINUE_DEGRADED);
    assertTrue(GatewayCatalog.FIELD_ACTION.length() > 0);
  }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `flox activate -- ./mvnw -pl :gateway-port -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=DocumentTest`
Expected: FAIL — compilation error "cannot find symbol Document / GatewayCatalog".

- [ ] **Step 7: Write `Document`, `GatewayCatalog`, `package-info`**

`Document.java`:

```java
package io.nxmatic.rke2lab.gateway.port;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The neutral envelope every host↔OSGi crossing carries: a document of a given type, owned by a
 * domain, whose body is a structured JSON tree. The host and OSGi share no data type — only this
 * record and the {@link JsonNode} payload cross. {@code coordinate} is the document type and the
 * schema key; {@code domain} names the owner. See docs/architecture/osgi/world-gateway-spec.adoc.
 */
public record Document(String domain, String coordinate, JsonNode payload) {}
```

`GatewayCatalog.java`:

```java
package io.nxmatic.rke2lab.gateway.port;

/**
 * The single source of truth for the gateway's string identifiers — coordinates, payload field
 * names, and enumerated field values. Call sites reference these constants, never literals, so a
 * mismatch cannot drift silently (the {@code clusterApi}-bug discipline). Build-time schemas (a later
 * increment) key on the coordinates here.
 */
public final class GatewayCatalog {

  /** The doctor domain owns the readiness vocabulary. */
  public static final String DOMAIN_DOCTOR = "doctor";

  /** Coordinate: the host's checkpoint outcome handed to the authority. */
  public static final String READINESS_CHECKPOINT = "readiness-checkpoint";

  /** Coordinate: the authority's provisioning verdict handed back. */
  public static final String READINESS_VERDICT = "readiness-verdict";

  /** Checkpoint payload: the scenario id (e.g. the systemd-adapter checkpoint slug). */
  public static final String FIELD_SCENARIO_ID = "scenarioId";

  /** Checkpoint payload: whether the checkpoint failed. */
  public static final String FIELD_FAILED = "failed";

  /** Checkpoint payload: the operator's raw severity override for this scenario, or absent. */
  public static final String FIELD_OVERRIDE = "override";

  /** Verdict payload: the provisioning action — {@link #ACTION_STOP} or {@link #ACTION_CONTINUE_DEGRADED}. */
  public static final String FIELD_ACTION = "action";

  /** Verdict payload: a human-readable reason for the action. */
  public static final String FIELD_REASON = "reason";

  /** Verdict action: stop provisioning (the failure is critical). */
  public static final String ACTION_STOP = "stop";

  /** Verdict action: continue in degraded mode (the failure is a warning). */
  public static final String ACTION_CONTINUE_DEGRADED = "continue-degraded";

  private GatewayCatalog() {}
}
```

`package-info.java`:

```java
@org.osgi.annotation.versioning.Version("1.0.0")
package io.nxmatic.rke2lab.gateway.port;
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `flox activate -- ./mvnw -pl :gateway-port -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=DocumentTest`
Expected: PASS, `Tests run: 2, Failures: 0`.

- [ ] **Step 9: Verify the bundle builds with the seam manifest**

Run: `flox activate -- ./mvnw -pl :gateway-port -am package -DskipTests -Dmaven.build.cache.skipCache=true`
Expected: `BUILD SUCCESS`. Then confirm the manifest carries the seam capability:
Run: `unzip -p osgi/gateway/gateway-port/target/gateway-port-0.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF | tr -d '\r' | grep -E "Provide-Capability|Export-Package"`
Expected: `Export-Package: io.nxmatic.rke2lab.gateway.port…` and `Provide-Capability: io.nxmatic.rke2lab.embed;type=seam`.

- [ ] **Step 10: Commit**

```bash
git add osgi/pom.xml osgi/gateway
git commit -m "feat(gateway): the gateway-port seam — Document envelope + GatewayCatalog"
```

---

## Task 2: The `ReadinessAuthority` verb + the doctor-core `@Component` impl

**Files:**
- Create: `osgi/gateway/gateway-port/src/main/java/io/nxmatic/rke2lab/gateway/port/ReadinessAuthority.java`
- Create: `osgi/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/internal/DefaultReadinessAuthority.java`
- Modify: `osgi/doctor/doctor-core/pom.xml` (add `gateway-port` dependency)
- Test: `osgi/doctor/doctor-core/src/test/java/io/nxmatic/rke2lab/doctor/ReadinessAuthorityTest.java`

**Interfaces:**
- Consumes: `Document`, `GatewayCatalog` (Task 1); `io.nxmatic.rke2lab.doctor.records.Severity` (an enum with `CRITICAL`, `WARNING`, and `static Optional<Severity> parse(String)` — bundle-side, legitimately used by doctor-core).
- Produces:
  - `interface ReadinessAuthority { Document assess(Document checkpoint); }` in `io.nxmatic.rke2lab.gateway.port`.
  - `DefaultReadinessAuthority` — an `@Component(service = ReadinessAuthority.class)` in `doctor-core`, holding the intrinsic severity per scenario id (`systemd-adapter → WARNING`), producing a `readiness-verdict` Document with `action = stop` iff the effective severity is `CRITICAL`.

- [ ] **Step 1: Create the `ReadinessAuthority` seam interface**

`ReadinessAuthority.java`:

```java
package io.nxmatic.rke2lab.gateway.port;

/**
 * The gateway verb the host calls to turn a readiness-checkpoint outcome into a provisioning
 * verdict. The host hands a {@code readiness-checkpoint} {@link Document} (the scenario id, whether
 * it failed, the operator's raw override) and receives a {@code readiness-verdict} {@link Document}
 * whose {@code action} field is {@code stop} or {@code continue-degraded}. The authority — not the
 * host — owns the severity vocabulary and the decision. See
 * docs/architecture/osgi/world-gateway-2a-document-foundation-spec.adoc.
 */
public interface ReadinessAuthority {

  /** Assess a checkpoint Document and return the provisioning verdict as a Document. */
  Document assess(Document checkpoint);
}
```

- [ ] **Step 2: Add the `gateway-port` dependency to doctor-core**

In `osgi/doctor/doctor-core/pom.xml`, add to `<dependencies>` (the impl implements the seam interface):

```xml
    <dependency>
      <groupId>io.nxmatic.rke2lab</groupId>
      <artifactId>gateway-port</artifactId>
      <version>${project.version}</version>
    </dependency>
```

- [ ] **Step 3: Write the failing test for `DefaultReadinessAuthority`**

`ReadinessAuthorityTest.java` — three cases: no override + intrinsic WARNING → continue-degraded; operator override "critical" → stop; override "warning" on an intrinsically-critical-less scenario → continue-degraded.

```java
package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.internal.DefaultReadinessAuthority;
import io.nxmatic.rke2lab.gateway.port.Document;
import io.nxmatic.rke2lab.gateway.port.GatewayCatalog;
import io.nxmatic.rke2lab.gateway.port.ReadinessAuthority;
import org.junit.jupiter.api.Test;

class ReadinessAuthorityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ReadinessAuthority authority = new DefaultReadinessAuthority();

  private static Document checkpoint(String scenarioId, boolean failed, String override) {
    final ObjectNode payload = MAPPER.createObjectNode();
    payload.put(GatewayCatalog.FIELD_SCENARIO_ID, scenarioId);
    payload.put(GatewayCatalog.FIELD_FAILED, failed);
    if (override != null) {
      payload.put(GatewayCatalog.FIELD_OVERRIDE, override);
    }
    return new Document(GatewayCatalog.DOMAIN_DOCTOR, GatewayCatalog.READINESS_CHECKPOINT, payload);
  }

  private static String action(Document verdict) {
    return verdict.payload().get(GatewayCatalog.FIELD_ACTION).asText();
  }

  @Test
  void intrinsicWarningContinuesDegraded() {
    final Document verdict = authority.assess(checkpoint("systemd-adapter", true, null));
    assertEquals(GatewayCatalog.READINESS_VERDICT, verdict.coordinate());
    assertEquals(GatewayCatalog.ACTION_CONTINUE_DEGRADED, action(verdict));
  }

  @Test
  void operatorCriticalOverrideStops() {
    final Document verdict = authority.assess(checkpoint("systemd-adapter", true, "critical"));
    assertEquals(GatewayCatalog.ACTION_STOP, action(verdict));
  }

  @Test
  void operatorWarningOverrideContinuesDegraded() {
    final Document verdict = authority.assess(checkpoint("systemd-adapter", true, "warning"));
    assertEquals(GatewayCatalog.ACTION_CONTINUE_DEGRADED, action(verdict));
  }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `flox activate -- ./mvnw -pl :doctor-core -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ReadinessAuthorityTest`
Expected: FAIL — "cannot find symbol DefaultReadinessAuthority".

- [ ] **Step 5: Implement `DefaultReadinessAuthority`**

`DefaultReadinessAuthority.java` (an SCR `@Component`; it builds the verdict with its own `ObjectMapper`; the intrinsic severities are the doctor's vocabulary, held here):

```java
package io.nxmatic.rke2lab.doctor.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.records.Severity;
import io.nxmatic.rke2lab.gateway.port.Document;
import io.nxmatic.rke2lab.gateway.port.GatewayCatalog;
import io.nxmatic.rke2lab.gateway.port.ReadinessAuthority;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * The OSGi-side readiness authority: it owns the severity vocabulary the host no longer holds. Given
 * a checkpoint Document (scenario id, failed, optional operator override), it resolves the effective
 * severity — the operator override if present, else the scenario's intrinsic severity — and maps it
 * to a provisioning verdict ({@code stop} iff CRITICAL, else {@code continue-degraded}). Published as
 * the {@link ReadinessAuthority} seam so the flat host reads only the verdict's action field.
 */
@Component(service = ReadinessAuthority.class)
public final class DefaultReadinessAuthority implements ReadinessAuthority {

  /**
   * Each checkpoint's intrinsic severity — the doctor's vocabulary. systemd-adapter: master can
   * provision without the dbus adapter (degraded), so a failure is a WARNING unless overridden.
   */
  private static final Map<String, Severity> INTRINSIC = Map.of("systemd-adapter", Severity.WARNING);

  private static final Severity DEFAULT_INTRINSIC = Severity.WARNING;

  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public Document assess(Document checkpoint) {
    final String scenarioId =
        checkpoint.payload().path(GatewayCatalog.FIELD_SCENARIO_ID).asText("");
    final String override =
        checkpoint.payload().hasNonNull(GatewayCatalog.FIELD_OVERRIDE)
            ? checkpoint.payload().get(GatewayCatalog.FIELD_OVERRIDE).asText()
            : null;

    final Severity effective =
        override != null
            ? Severity.parse(override).orElseGet(() -> intrinsicFor(scenarioId))
            : intrinsicFor(scenarioId);

    final boolean stop = effective == Severity.CRITICAL;
    final ObjectNode verdict = mapper.createObjectNode();
    verdict.put(
        GatewayCatalog.FIELD_ACTION,
        stop ? GatewayCatalog.ACTION_STOP : GatewayCatalog.ACTION_CONTINUE_DEGRADED);
    verdict.put(
        GatewayCatalog.FIELD_REASON,
        scenarioId + " severity=" + effective.name().toLowerCase());
    return new Document(GatewayCatalog.DOMAIN_DOCTOR, GatewayCatalog.READINESS_VERDICT, verdict);
  }

  private Severity intrinsicFor(String scenarioId) {
    return INTRINSIC.getOrDefault(scenarioId, DEFAULT_INTRINSIC);
  }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `flox activate -- ./mvnw -pl :doctor-core -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ReadinessAuthorityTest`
Expected: PASS, `Tests run: 3, Failures: 0`.

- [ ] **Step 7: Verify doctor-core still builds (the @Component is picked up by bnd SCR processing)**

Run: `flox activate -- ./mvnw -pl :doctor-core -am package -DskipTests -Dmaven.build.cache.skipCache=true`
Expected: `BUILD SUCCESS`. Confirm the SCR descriptor was generated:
Run: `unzip -l osgi/doctor/doctor-core/target/doctor-core-0.0.0-SNAPSHOT.jar | grep -E "OSGI-INF/.*ReadinessAuthority|OSGI-INF" | head`
Expected: an `OSGI-INF/…DefaultReadinessAuthority.xml` entry (bnd's DS component descriptor).

- [ ] **Step 8: Commit**

```bash
git add osgi/gateway/gateway-port/src/main/java/io/nxmatic/rke2lab/gateway/port/ReadinessAuthority.java osgi/doctor/doctor-core
git commit -m "feat(gateway): ReadinessAuthority verb + doctor-core @Component authority"
```

---

## Task 3: `ControlplanePolicy` + `from()` doctor-free (raw config)

**Files:**
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/policy/ControlplanePolicy.java`
- Test: `exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/policy/ControlplanePolicyRawConfigTest.java`

**Interfaces:**
- Consumes: `Rke2labConfig` (existing; `config.policy().readinessOverride()` and `config.policy().previewSimulate()` both return `Map<String,String>`).
- Produces: `ControlplanePolicy` with NO `doctor.records` import. `ReadinessPolicy` now holds `Map<String,String> rawOverrides` with `Optional<String> rawOverride(String scenarioId)`. `PreviewPolicy` now holds `Map<String,String> rawSimulations` with `Optional<String> rawSimulate(String scenarioId)`. The `toOutputMap()` of each emits the raw strings unchanged. (The host carries raw strings; OSGi interprets them — Task 2 for readiness; 2B for preview.)

- [ ] **Step 1: Write the failing test**

`ControlplanePolicyRawConfigTest.java` — asserts the policy carries the raw override/simulate strings and (the key constraint) that the class has no `doctor.records` dependency. The no-doctor-import is enforced structurally: the test references only raw strings; the build (Step 4) is what proves the import is gone, but we also assert the accessor shape.

```java
package io.nxmatic.rke2lab.controlplane.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ControlplanePolicyRawConfigTest {

  @Test
  void readinessOverrideIsCarriedRaw() {
    final ControlplanePolicy.ReadinessPolicy readiness =
        new ControlplanePolicy.ReadinessPolicy(java.util.Map.of("systemd-adapter", "critical"));
    assertEquals(Optional.of("critical"), readiness.rawOverride("systemd-adapter"));
    assertTrue(readiness.rawOverride("absent").isEmpty());
  }

  @Test
  void previewSimulateIsCarriedRaw() {
    final ControlplanePolicy.PreviewPolicy preview =
        new ControlplanePolicy.PreviewPolicy(
            java.util.Map.of("systemd-adapter", "connection-refused"));
    assertEquals(Optional.of("connection-refused"), preview.rawSimulate("systemd-adapter"));
    assertTrue(preview.rawSimulate("absent").isEmpty());
  }

  @Test
  void rawOverrideSurfacesInOutputs() {
    final ControlplanePolicy.ReadinessPolicy readiness =
        new ControlplanePolicy.ReadinessPolicy(java.util.Map.of("systemd-adapter", "critical"));
    assertEquals("critical", readiness.toOutputMap().get("readiness.override.systemd-adapter"));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `flox activate -- ./mvnw -pl :seed-master -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ControlplanePolicyRawConfigTest`
Expected: FAIL — the constructors still take `Map<String,Severity>`/`Map<String,Symptom>`, no `rawOverride`/`rawSimulate` methods.

- [ ] **Step 3: Rewrite `ReadinessPolicy` and `PreviewPolicy` to hold raw config**

In `ControlplanePolicy.java`: remove the imports `io.nxmatic.rke2lab.doctor.records.Severity` and `io.nxmatic.rke2lab.doctor.records.Symptom`. Remove `toSeverityOverrides` and `toSimulations`. Change `from()` lines 79-80 to pass the raw maps directly. Rewrite the two nested records:

```java
  // in from():
        .readiness(new ReadinessPolicy(config.policy().readinessOverride()))
        .preview(new PreviewPolicy(config.policy().previewSimulate()))
```

```java
  /**
   * Operator override of readiness-scenario severity, keyed by scenario id (e.g. {@code
   * "systemd-adapter"}), carried as the RAW config string. The host does not interpret it — it hands
   * the raw value to the OSGi-side ReadinessAuthority, which owns the severity vocabulary and decides.
   */
  public record ReadinessPolicy(Map<String, String> rawOverrides) {
    public ReadinessPolicy {
      rawOverrides = Map.copyOf(rawOverrides);
    }

    public static ReadinessPolicy none() {
      return new ReadinessPolicy(Map.of());
    }

    /** The operator's raw override string for a scenario, if any — interpreted OSGi-side. */
    public Optional<String> rawOverride(String scenarioId) {
      return Optional.ofNullable(rawOverrides.get(scenarioId));
    }

    public Map<String, Object> toOutputMap() {
      final Map<String, Object> outputs = new LinkedHashMap<>();
      rawOverrides.forEach((scenario, value) -> outputs.put("readiness.override." + scenario, value));
      return outputs;
    }
  }
```

```java
  /**
   * Operator control of {@code pulumi preview} fault simulation, keyed by scenario id, carried as the
   * RAW config string. Preview-only by construction. The host does not interpret it; the simulated
   * probe path (a later increment) maps it OSGi-side.
   */
  public record PreviewPolicy(Map<String, String> rawSimulations) {
    public PreviewPolicy {
      rawSimulations = Map.copyOf(rawSimulations);
    }

    public static PreviewPolicy none() {
      return new PreviewPolicy(Map.of());
    }

    /** The fake-incident symptom string ordered for a scenario, if any — interpreted OSGi-side. */
    public Optional<String> rawSimulate(String scenarioId) {
      return Optional.ofNullable(rawSimulations.get(scenarioId));
    }

    public Map<String, Object> toOutputMap() {
      final Map<String, Object> outputs = new LinkedHashMap<>();
      rawSimulations.forEach((scenario, value) -> outputs.put("preview.simulate." + scenario, value));
      return outputs;
    }
  }
```

NOTE: this changes `PreviewPolicy.simulate()` (returned `Optional<Symptom>`) to `rawSimulate()` (returns `Optional<String>`). `SystemdAdapterStage:88` calls `policy.preview().simulate(SCENARIO_ID)` — that call site is updated in Task 4 (it is part of the same stage transform). Until Task 4, the stage will not compile against this change — so Task 3 and Task 4 form one compile unit: **run the seed-master test build only after Task 4**, OR keep Task 3's verification scoped to the policy test class compiling its own test. To keep each task green, Task 3 ALSO applies the minimal call-site fix at `SystemdAdapterStage:87-88` and `:175` to keep the module compiling — see Step 3b.

- [ ] **Step 3b: Keep the module compiling — minimal call-site bridge**

Because removing the typed accessors breaks `SystemdAdapterStage`, apply the SMALLEST change that compiles, deferring the real verdict-crossing to Task 4. At `SystemdAdapterStage` line 87-88, the preview-simulate currently yields `Optional<Symptom>`; bridge it to raw for now by mapping through the existing `Symptom.parse` LOCALLY in the stage (the stage still legitimately imports `Symptom` for the consult path until 2B):

```java
    // line 87-88 — bridge: the policy now carries raw; the stage still parses Symptom for the probe
    // path (removed in 2B). preview-simulate interpretation stays here until the probe path migrates.
    final Optional<Symptom> simulated =
        preview
            ? policy.preview().rawSimulate(SCENARIO_ID).flatMap(Symptom::parse)
            : Optional.empty();
```

At line 175, the readiness override now comes raw; bridge it the same way to keep the `== CRITICAL` working until Task 4 replaces it with the verdict Document:

```java
    // line 175 — bridge: raw override parsed locally; replaced by the ReadinessAuthority verdict in
    // the next increment of this stage.
    final Severity effective =
        policy.readiness().rawOverride(SCENARIO_ID).flatMap(Severity::parse).orElse(INTRINSIC_SEVERITY);
```

This keeps `SystemdAdapterStage` compiling and its behaviour identical; Task 4 removes both bridges and the `Severity` import.

- [ ] **Step 4: Run the test to verify it passes + the module compiles**

Run: `flox activate -- ./mvnw -pl :seed-master -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ControlplanePolicyRawConfigTest`
Expected: PASS, `Tests run: 3, Failures: 0`, and the module compiles (the bridges keep `SystemdAdapterStage` valid).

- [ ] **Step 5: Verify `ControlplanePolicy` no longer references doctor.records**

Run: `grep -n "doctor.records" exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/policy/ControlplanePolicy.java`
Expected: no output (the policy is doctor-free — `from()` no longer parses, the records are gone).

- [ ] **Step 6: Commit**

```bash
git add exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/policy/ControlplanePolicy.java exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterStage.java exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/policy/ControlplanePolicyRawConfigTest.java
git commit -m "refactor(seed): ControlplanePolicy holds raw config, from() is doctor-free"
```

---

## Task 4: `SystemdAdapterStage` reads the verdict via `ReadinessAuthority` + pipeline wiring

**Files:**
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterStage.java`
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/BootstrapPipeline.java`
- Modify: `exec/seed-master/pom.xml` (add `gateway-port` dependency)
- Test: `exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterVerdictTest.java`

**Interfaces:**
- Consumes: `Document`, `GatewayCatalog`, `ReadinessAuthority` (Tasks 1-2); `ControlplanePolicy.ReadinessPolicy.rawOverride` (Task 3); `BootedFramework.awaitService(Class, long)` (existing); the existing `SystemdAdapterStage` constructor (10 args) — this task replaces the `policy`-derived Severity branch with a `ReadinessAuthority`-derived action.
- Produces: `SystemdAdapterStage` constructor gains a `ReadinessAuthority readinessAuthority` parameter (replacing the host's `Severity` reasoning); `BootstrapPipeline` resolves it via `awaitService(ReadinessAuthority.class, 5000)` and injects it. After this task `SystemdAdapterStage` no longer imports `Severity`.

- [ ] **Step 1: Add the `gateway-port` dependency to seed-master**

In `exec/seed-master/pom.xml`, add (near the existing `domain-annotations` dep):

```xml
    <!-- The world-gateway seam: Document + ReadinessAuthority. The stage builds a checkpoint
         Document and reads the verdict's action, instead of reasoning on Severity. -->
    <dependency>
      <groupId>io.nxmatic.rke2lab</groupId>
      <artifactId>gateway-port</artifactId>
      <version>${project.version}</version>
    </dependency>
```

- [ ] **Step 2: Write the failing test for the verdict-driven stop/continue decision**

`SystemdAdapterVerdictTest.java` — drives the stage's decision through a fake `ReadinessAuthority`: a `stop` verdict throws `TopicFailure`; a `continue-degraded` verdict does not. The test injects a fake authority and a failing probe.

```java
package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.gateway.port.Document;
import io.nxmatic.rke2lab.gateway.port.GatewayCatalog;
import io.nxmatic.rke2lab.gateway.port.ReadinessAuthority;
import io.nxmatic.rke2lab.pipeline.TopicFailure;
import org.junit.jupiter.api.Test;

class SystemdAdapterVerdictTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ReadinessAuthority authorityReturning(String action) {
    return checkpoint -> {
      final ObjectNode verdict = MAPPER.createObjectNode();
      verdict.put(GatewayCatalog.FIELD_ACTION, action);
      verdict.put(GatewayCatalog.FIELD_REASON, "test");
      return new Document(
          GatewayCatalog.DOMAIN_DOCTOR, GatewayCatalog.READINESS_VERDICT, verdict);
    };
  }

  @Test
  void stopVerdictThrowsTopicFailure() {
    final SystemdAdapterStage stage =
        SystemdAdapterStageFixture.failing(authorityReturning(GatewayCatalog.ACTION_STOP));
    assertThrows(TopicFailure.class, stage::launch);
  }

  @Test
  void continueDegradedVerdictDoesNotThrow() {
    final SystemdAdapterStage stage =
        SystemdAdapterStageFixture.failing(
            authorityReturning(GatewayCatalog.ACTION_CONTINUE_DEGRADED));
    assertDoesNotThrow(stage::launch);
  }
}
```

This references a small test fixture `SystemdAdapterStageFixture.failing(ReadinessAuthority)` that constructs the stage with a probe that always produces a failed `Observation` and the other collaborators stubbed. Create it alongside the test:

`SystemdAdapterStageFixture.java` (test sources):

```java
package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.gateway.port.ReadinessAuthority;
import java.util.Map;
import java.util.Optional;

/** Builds a SystemdAdapterStage whose probe always fails, for verdict-decision tests. */
final class SystemdAdapterStageFixture {

  private SystemdAdapterStageFixture() {}

  static SystemdAdapterStage failing(ReadinessAuthority authority) {
    final BootstrapConfig config = BootstrapConfig.from(io.nxmatic.rke2lab.controlplane.config.Rke2labConfig.defaults());
    final ControlplanePolicy policy = ControlplanePolicy.defaults();
    final SystemdAdapterProbe failingProbe =
        cfg ->
            Observation.failed(
                Symptom.CONNECTION_REFUSED, "fake failure", Map.of("source", "test"));
    return new SystemdAdapterStage(
        config,
        policy,
        false, // pulumiMode off → no dry-run, step bodies run
        message -> {},
        null, // runbook
        null, // consultations
        null, // doctor (consult is skipped when null? — see Step 3 note)
        failingProbe,
        summary -> {},
        authority);
  }
}
```

NOTE: the fixture passes `doctor=null`; the stage's `consultDoctor` must tolerate a null doctor for the decision test (it already guards a null `consultations`; confirm/guard `doctor` too in Step 3, since the verdict decision is independent of the consult). If the stage cannot tolerate a null doctor, the fixture supplies a no-op `ConsultingService` instead — Step 3 decides based on the real code.

- [ ] **Step 3: Run the test to verify it fails**

Run: `flox activate -- ./mvnw -pl :seed-master -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=SystemdAdapterVerdictTest`
Expected: FAIL — the `SystemdAdapterStage` constructor does not yet take a `ReadinessAuthority`.

- [ ] **Step 4: Transform `SystemdAdapterStage` to read the verdict Document**

In `SystemdAdapterStage.java`:
1. Remove `import io.nxmatic.rke2lab.doctor.records.Severity;`.
2. Add `import io.nxmatic.rke2lab.gateway.port.Document;`, `import io.nxmatic.rke2lab.gateway.port.GatewayCatalog;`, `import io.nxmatic.rke2lab.gateway.port.ReadinessAuthority;`, `import com.fasterxml.jackson.databind.ObjectMapper;`, `import com.fasterxml.jackson.databind.node.ObjectNode;`.
3. Remove the `INTRINSIC_SEVERITY` constant (the intrinsic severity now lives in `DefaultReadinessAuthority`).
4. Add a `ReadinessAuthority readinessAuthority` field + constructor parameter (last param) + a `private final ObjectMapper mapper = new ObjectMapper();`.
5. Replace the failure branch (the old lines 174-182) with the verdict crossing:

```java
    // Failure: the patient consults (the consult path stays until 2B), then the host asks the OSGi
    // authority for the provisioning verdict — it owns the severity vocabulary, the host reads only
    // the action field. No Severity on the host.
    consultDoctor(captured);

    final Document checkpoint = checkpointDocument(SCENARIO_ID);
    final Document verdict = readinessAuthority.assess(checkpoint);
    final String action = verdict.payload().path(GatewayCatalog.FIELD_ACTION).asText();
    if (GatewayCatalog.ACTION_STOP.equals(action)) {
      log("✗ " + SCENARIO_ID + " FAILED, verdict=stop → stopping provisioning");
      throw new TopicFailure("systemd adapter", failure);
    }
    log("⚠ " + SCENARIO_ID + " FAILED, verdict=continue-degraded → continuing in DEGRADED mode");
    sink.accept(degradedObservation(failure).toOutputMap());
    return this;
```

6. Add the checkpoint builder (the host's native facts → a structured Document; the raw override comes from the policy):

```java
  /** The checkpoint outcome as a structured Document for the readiness authority. */
  private Document checkpointDocument(String scenarioId) {
    final ObjectNode payload = mapper.createObjectNode();
    payload.put(GatewayCatalog.FIELD_SCENARIO_ID, scenarioId);
    payload.put(GatewayCatalog.FIELD_FAILED, true);
    policy
        .readiness()
        .rawOverride(scenarioId)
        .ifPresent(value -> payload.put(GatewayCatalog.FIELD_OVERRIDE, value));
    return new Document(
        GatewayCatalog.DOMAIN_DOCTOR, GatewayCatalog.READINESS_CHECKPOINT, payload);
  }
```

7. Update the constructor signature + assignment to include `readinessAuthority`. If `consultDoctor` does not already tolerate a null doctor, guard it: `if (doctor == null || observation == null || observation.symptom().isEmpty()) return;` (the verdict decision must not depend on the consult).

- [ ] **Step 5: Update the construction site in `BootstrapPipeline`**

In `BootstrapPipeline.java`:
1. Add `import io.nxmatic.rke2lab.gateway.port.ReadinessAuthority;`.
2. Add a `ReadinessAuthority readinessAuthority;` field to `PipelineState`.
3. Add a resolve method mirroring `resolveClusterReadinessContact`, and call it in the same burst (after `resolveClusterReadinessContact(state);` at both sites ~line 139 and ~148):

```java
    private static void resolveReadinessAuthority(PipelineState state) {
      final ReadinessAuthority authority =
          state.bootedFramework.awaitService(ReadinessAuthority.class, 5000);
      if (authority == null) {
        throw new IllegalStateException(
            "No ReadinessAuthority published in the OSGi registry within 5s "
                + "(doctor-core DefaultReadinessAuthority @Component absent).");
      }
      state.readinessAuthority = authority;
    }
```

4. In `AwaitingSystemdAdapter.during(...)`, pass `state.readinessAuthority` as the new last constructor argument to `new SystemdAdapterStage(...)`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `flox activate -- ./mvnw -pl :seed-master -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=SystemdAdapterVerdictTest`
Expected: PASS, `Tests run: 2, Failures: 0`.

- [ ] **Step 7: Verify `SystemdAdapterStage` no longer imports `Severity`**

Run: `grep -n "doctor.records.Severity\|Severity\." exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterStage.java`
Expected: no output (the stage no longer reasons on `Severity`; it still imports `Symptom`/`Observation` for the consult path — those are 2B).

- [ ] **Step 8: Full-reactor verify — build green + the gate worklist shrank**

First rebuild the staging extension (loaded from `~/.m2` before the reactor), then the full reactor:

Run: `flox activate -- ./mvnw -f maven-embed-staging-ext/pom.xml install -DskipTests -Dmaven.build.cache.skipCache=true`
Then: `flox activate -- ./mvnw package -Pall-worlds -DskipTests -Dmaven.build.cache.skipCache=true 2>&1 | tee /tmp/2a-verify.log`
Expected: `BUILD SUCCESS`, and the `realm-boundary` summary shows FEWER warns than 44 — the three `ControlplanePolicy*` entries are gone (the policy is doctor-free) and `SystemdAdapterStage`'s Severity reference is gone (it stays in the list only for its remaining `Symptom`/`Observation` refs).

Run: `grep -E "realm-boundary:|ControlplanePolicy" /tmp/2a-verify.log`
Expected: `realm-boundary: 0 error, N warn` with N < 44; NO `ControlplanePolicy` lines in the worklist.

- [ ] **Step 9: Commit**

```bash
git add exec/seed-master/pom.xml exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterStage.java exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/BootstrapPipeline.java exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/
git commit -m "feat(seed): systemd-adapter reads the readiness verdict as a Document, drops Severity"
```

---

## Self-review checklist

This plan delivers 2A: the `Document` foundation (Task 1), the `ReadinessAuthority` verb + OSGi authority (Task 2), the policy made doctor-free (Task 3), and the verdict crossing wired into the stage + pipeline (Task 4). After 2A, `ControlplanePolicy`/`from()` load zero doctor types (the boot-parse crash trigger is closed); the remaining worklist (probe/consult/egress/readside) is 2B/2C/2D. Per the spec, `pulumi preview` boots green only at the LAST increment — 2A is build-green with a shrunk `REALM_BOUNDARY` worklist.
