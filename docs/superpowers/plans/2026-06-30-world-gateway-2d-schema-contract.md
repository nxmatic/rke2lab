# World Gateway 2D — the Document contract (JSON Schema per coordinate) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the opaque `Document(domain, coordinate, payload:String)` a contract of form — one JSON Schema per coordinate, owned OSGi-side, build-enforced by a fourth staging gate `SCHEMA_CONCORD`, flipped `WARN`→`ERROR` so the branch merges with TWO gates locked (`REALM_BOUNDARY` + `SCHEMA_CONCORD`).

**Architecture:** Six JSON Schema files live in `doctor-core/src/main/resources/schema/<slug>.schema.json`, anchored by `Coordinate` slug (no `DomainDagMapper` façade). A new `SCHEMA_CONCORD` staging law (added to BOTH `StagingGate` enums) checks per coordinate: (a) the schema is valid against the JSON-Schema meta-schema (via networknt), and (b) the `WorldGatewayCatalog.FIELD_*` the coordinate's producer/consumer code references == the schema's declared `properties`. A per-realm `DocumentCodec` (OSGi `@Component` twin of `YamlMapper` + a plain host instance) is wired but its runtime validation is OFF in embedded (the capstone turns it on).

**Tech Stack:** Java 25 (flox toolchain), Maven multi-module via reactor (`-am`), bnd OSGi bundles, the `maven-embed-staging-ext` build extension (ASM bytecode introspection), jackson 2.22.0 (already bundled), `com.networknt:json-schema-validator` (new), JUnit 5.

## Global Constraints

[Copied verbatim from `docs/architecture/osgi/world-gateway-2d-schema-contract-spec.adoc` and CLAUDE.md.]

- **Build through flox always:** `flox activate -- ./mvnw …`. Never `mvn install` to `~/.m2`; inter-module deps resolve through the reactor — every module build uses `-am`.
- **Tests are skipped by default** (root `.mvn` config). Execute with `-DskipTests=false`. The OSGi/in-container tests run only under `-Pall-worlds`.
- **Full-reactor gate command:** `flox activate -- ./mvnw clean package -DskipTests=false -Pall-worlds -Dmaven.build.cache.enabled=false`. Cache OFF is mandatory for a trustworthy gate verdict (a stale cache masked both a staging change and pre-existing test breakage on 2026-06-30).
- **Two-phase build for the extension:** changing `maven-embed-staging-ext` requires the two-phase dance — the live extension self-poisons its own rebuild. Disable the `staging-extension` block in `.mvn/extensions.xml` (comment it out) → `./mvnw -f maven-embed-staging-ext/pom.xml clean install -DskipTests=false` → re-enable the block → run the reactor. See `osgi-staging-extension-chantier` memory.
- **The two `StagingGate` enums must stay in step:** `osgi/foundation/domain-annotations/.../StagingGate.java` (the `@GovernedBy` value) and `maven-embed-staging-ext/.../StagingGate.java` (the ASM mirror, read by constant NAME). Adding a constant means editing BOTH.
- **`Document.payload` stays a `String`** — the logical contract. No carrier/streaming change (transport/remote backlog).
- **Identifier discipline:** never hardcode field-name or coordinate strings; use `WorldGatewayCatalog.FIELD_*` and `Coordinate.<X>.slug()`. (The `clusterApi`-bug discipline.)
- **No dead code, no back-compat shims, no `@Deprecated`** in this single-developer repo — delete the old path in the same change.
- **networknt must follow the realm-library rule** established 2026-06-30 (commit `ae46278b`): a third-party OSGi bundle a domain bundle imports is staged flat∧bundle automatically; a package the boot-stack already provides is NOT staged. networknt brings no `org.slf4j`-style conflict, so it self-includes with zero staging code — verify, don't add a hand-list.

---

## File Structure

**New files:**
- `osgi/domains/doctor/doctor-core/src/main/resources/schema/readiness-checkpoint.schema.json` (+ 5 siblings: `readiness-verdict`, `consultation`, `intervention-request`, `intervention`, `visit`)
- `osgi/domains/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/DocumentCodec.java` — OSGi `@Component` (JSON twin of `YamlMapper`)
- `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/DocumentCodec.java` — plain host instance (host has no DS) — OR a shared-shape pattern decided in Task 2
- `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/SchemaConcord.java` — the gate (meta-schema validity + concord), ASM-side
- `maven-embed-staging-ext/staging-extension/src/main/java/io/nxmatic/rke2lab/maven/staging/CoordinateFieldUsage.java` — ASM helper: which `FIELD_*` a class references near a `Coordinate.<X>` use (the part-(b) engine)
- `maven-embed-staging-ext/staging-extension/src/test/java/io/nxmatic/rke2lab/maven/staging/SchemaConcordTest.java`
- doctor-core round-trip tests per coordinate (one test class, six methods, or six classes)

**Modified files:**
- `bom/pom.xml` — pin `com.networknt:json-schema-validator` (+ manage its transitive `com.ethlo.time:itu` if needed)
- `osgi/foundation/domain-annotations/.../StagingGate.java` — add `SCHEMA_CONCORD`
- `maven-embed-staging-ext/.../StagingGate.java` — add `SCHEMA_CONCORD` (mirror)
- `maven-embed-staging-ext/.../StagingExecutionStrategy.java` — wire the new gate into `enforceGates` + the summary
- `osgi/domains/doctor/doctor-core/pom.xml` + `bnd.bnd` — depend on / bundle networknt; expose the schema resources
- doctor-core `package-info.java` — `@GovernedBy(SCHEMA_CONCORD, WARN)` during the worklist, removed at the flip
- the ~6 producer/consumer classes (per coordinate) only if a FIELD_* / schema mismatch must be reconciled

---

## RISK NOTE — the part-(b) concord engine is the real engineering

Part (a) (meta-schema validity) is mechanical: hand a `JsonNode` of the schema to networknt's meta-schema validator. Part (b) is the hard part: **discover which `FIELD_*` constants a coordinate's code references**, to compare against the schema's `properties`. The codebase already reads method bodies via ASM (`ReferencedTypes` in the staging extension, no `SKIP_CODE`). The approach this plan takes (Task 3):

- A `FIELD_*` reference compiles to a `GETSTATIC io/nxmatic/.../WorldGatewayCatalog.FIELD_X : String`. The **constant VALUE** ("scenarioId", …) is what lands in the schema's `properties` keys, so the gate must map `FIELD_X` → its string value. `WorldGatewayCatalog` is on the staging classpath as a class; read its `ConstantValue` attributes via ASM (the static-final String fields are inlined as `ConstantValue`).
- A coordinate is named via `Coordinate.X` → `GETSTATIC …/Coordinate.X`. The gate associates the `FIELD_*` set with the `Coordinate` used **in the same class** (the producer/consumer classes are coordinate-specific per the §3 map — verified: `DefaultReadinessAuthority` builds `READINESS_VERDICT` and references `FIELD_*` in `assess()`). Class-granularity is sufficient for the 2C reality (N readers per coordinate, each single-coordinate); do NOT attempt method-granularity unless a class is shown to straddle two coordinates.

This plan front-loads that engine (Task 3) and proves it on ONE coordinate (`readiness-verdict`, Task 4) before scaling to the other five (Tasks 5–9). If the class-granularity assumption breaks for a specific coordinate, that coordinate's task records it and narrows scope — do not silently widen.

---

## Task 1: Pin and bundle networknt (zone-0a)

**Files:**
- Modify: `bom/pom.xml` (dependencyManagement)
- Modify: `osgi/domains/doctor/doctor-core/pom.xml` (dependency)
- Verify: `exec/seed-master/target/*-exec.jar` staging (networknt self-includes as a realm library)

**Interfaces:**
- Produces: `com.networknt.schema.*` available on the doctor-core bundle classpath and (flat) on the host.

- [ ] **Step 1: Add the networknt version property + management entry to `bom/pom.xml`**

Find the `<properties>` block (jackson.version is at line 32) and add:
```xml
<networknt.json-schema.version>1.5.6</networknt.json-schema.version>
```
In `<dependencyManagement><dependencies>`, after the jackson-bom import, add:
```xml
<dependency>
  <groupId>com.networknt</groupId>
  <artifactId>json-schema-validator</artifactId>
  <version>${networknt.json-schema.version}</version>
</dependency>
```
(Confirm 1.5.6 resolves a jackson-2.22-compatible build; if the reactor reports a jackson downgrade, pin to the latest networknt that targets jackson 2.x and record the exact version here.)

- [ ] **Step 2: Add the dependency to doctor-core**

In `osgi/domains/doctor/doctor-core/pom.xml`, add (no version — managed by the bom):
```xml
<dependency>
  <groupId>com.networknt</groupId>
  <artifactId>json-schema-validator</artifactId>
</dependency>
```

- [ ] **Step 3: Build doctor-core through the reactor**

Run: `flox activate -- ./mvnw -pl :doctor-core -am clean package -DskipTests=false`
Expected: BUILD SUCCESS. doctor-core's bnd computes an `Import-Package` for `com.networknt.schema`.

- [ ] **Step 4: Verify networknt self-includes as a realm library (no staging code change)**

Run: `flox activate -- ./mvnw -pl :seed-master -am clean package -DskipTests -Pall-worlds -Dmaven.build.cache.enabled=false`
Then: `unzip -l exec/seed-master/target/seed-master-0.0.0-SNAPSHOT-exec.jar | grep -iE 'json-schema-validator|META-INF/bundles/.*networknt'`
Expected: `json-schema-validator-*.jar` present under `META-INF/bundles/` (staged as a realm library — doctor-core imports `com.networknt.schema`). If networknt drags a package the boot-stack already provides, the realm-library bound (commit `ae46278b`) excludes it automatically — confirm no resolution error in the build log. NO staging-extension edit should be needed; if one seems necessary, STOP and reassess (the rule should be self-deriving).

- [ ] **Step 5: Commit**

```bash
git add bom/pom.xml osgi/domains/doctor/doctor-core/pom.xml
git commit -m "feat(2d): pin + bundle networknt json-schema-validator (realm library, self-included)"
```

---

## Task 2: The DocumentCodec (zone-0b) — ONE source, loaded per realm, validation OFF

**Files:**
- Create: `osgi/foundation/document-codec/pom.xml` (a plain flat jar — NO bnd, NO embed capability)
- Create: `osgi/foundation/document-codec/src/main/java/io/nxmatic/rke2lab/document/DocumentCodec.java`
- Create: `osgi/foundation/document-codec/src/test/java/io/nxmatic/rke2lab/document/DocumentCodecTest.java`
- Modify: `osgi/foundation/pom.xml` (add the module)
- Modify: `osgi/domains/doctor/doctor-core/pom.xml` (depend on document-codec) + `bnd.bnd` (nest it)
- Modify: `exec/seed-master/pom.xml` (depend on document-codec — shaded flat)

**Interfaces:**
- Produces: `io.nxmatic.rke2lab.document.DocumentCodec` with `String encode(JsonNode)`, `JsonNode decode(String)`, `boolean validate(String payload, String schemaSlug)` (INERT — returns `true` — while validation is off), and `DocumentCodec withValidation(boolean)`. ONE class, consumed by both realms.

**NEW PATTERN — nesting one of OUR OWN flat modules into a bundle classpath (decision 5.2).** Until now we have nested only THIRD-PARTY jars (cdk8s, dbus-java, jsr310) into a carrier's Bundle-ClassPath. This task nests one of our own modules the same way, and it is the first time — capture it as a project pattern if the build proves it out. The reasoning:

The codec uses jackson, which since the realm-library isolation (commit `ae46278b`) is loaded per realm — flat host-side, bundle OSGi-side. We want ONE source of codec logic, but TWO runtime copies each bound to its realm's jackson, and NO codec type crossing the String-only seam. The carrier/nesting mechanism delivers exactly that WITHOUT touching the staging rules:

- `document-codec` is a **plain flat jar** — no `Bundle-SymbolicName`, no `Provide-Capability`. So `StagingClosure.isRealmLibrary` (which requires `b.isBundle()`) never selects it for autonomous bundle staging; it is invisible to the realm-library rule.
- The **host** (`seed-master`) depends on it normally → it is shaded **flat** into the uber-jar, binding to the host's flat jackson — like any host dependency.
- **OSGi** (`doctor-core`) depends on it AND nests it via `-includeresource: document-codec-*.jar;lib:=true` → its classes ride doctor-core's Bundle-ClassPath, binding to the bundle's jackson — exactly the cdk8s/jsr310 mechanism.
- `DUPLICATE_REALM_CLASS` is NOT triggered: its violation is `flat ∧ seamSurface`, and `io.nxmatic.rke2lab.document` is not a seam package (no `type=seam` bundle exports it). flat∧nested is fine.
- It lives in `osgi/foundation/` because that aggregate builds BEFORE `host`/`exec` (so doctor-core can nest it AND seed-master can depend on it — `world-gateway` already proves foundation→host visibility), and because `host/` builds after `osgi/`, so the codec could NOT live host-side (doctor-core could not depend on an unbuilt host module).

**No `@Component` in 2D (YAGNI).** The spec foresaw an `@Component` twin of `YamlMapper`, but in 2D nothing injects the codec by `@Reference` — the ~19 payload-construction sites use `new ObjectMapper()` and migrating them is the §8 follow-up (`document-codec-instance-in-2d-backlog`). So the codec is a plain class consumed by `new DocumentCodec()`. When the migration lands, a thin `@Component` in doctor-core can publish it (DS reads the host bundle's own classes, not nested jars — so the `@Component`, if added then, lives in doctor-core's own `src`, delegating to the nested codec; same shape as `Cdk8sApps`).

- [ ] **Step 1: Create the `document-codec` module pom (flat jar, jackson dependency)**

`osgi/foundation/document-codec/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.nxmatic.rke2lab</groupId>
    <artifactId>bundle-parent</artifactId>
    <version>0.0.0-SNAPSHOT</version>
    <relativePath>../../bundle-parent/pom.xml</relativePath>
  </parent>
  <artifactId>document-codec</artifactId>
  <name>osgi/foundation/document-codec</name>
  <description>The Document payload codec logic, written once and loaded per realm: shaded flat into
    the host, nested into doctor-core's Bundle-ClassPath OSGi-side (-includeresource;lib:=true). A
    plain flat jar (no Bundle-SymbolicName) so the realm-library rule never stages it as an
    autonomous bundle — each realm binds it to its own jackson, no codec type crosses the seam.</description>
  <dependencies>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
  </dependencies>
</project>
```
NOTE: confirm `bundle-parent` does not force a bnd `Bundle-SymbolicName` on every child. If it does (the parent runs bnd-process by default), this module must instead inherit from a plain-jar parent or disable the bnd execution — the module MUST emit a plain jar with no OSGi manifest, or `isRealmLibrary` could pick it up. Verify the produced `target/document-codec-*.jar` MANIFEST.MF has NO `Bundle-SymbolicName` before proceeding; if it has one, switch the parent to `build-parent` (the non-bundle parent) and re-verify.

- [ ] **Step 2: Add the module to `osgi/foundation/pom.xml`**

Add `<module>document-codec</module>` to the `<modules>` list (alongside `world-gateway`, `domain-annotations`, `pipeline`).

- [ ] **Step 3: Write the failing test**

`osgi/foundation/document-codec/src/test/java/io/nxmatic/rke2lab/document/DocumentCodecTest.java`:
```java
package io.nxmatic.rke2lab.document;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocumentCodecTest {

  @Test
  void encodeDecodeRoundTripsAndValidationIsInertByDefault() {
    final DocumentCodec codec = new DocumentCodec();
    final String json = codec.encode(codec.decode("{\"action\":\"hold\"}"));
    assertTrue(json.contains("\"action\""));
    // validation OFF by default: a payload matching no schema still passes (the embedded posture —
    // the OSGi reader that parses the payload is the implicit validator).
    assertTrue(codec.validate("{\"unexpected\":1}", "readiness-verdict"),
        "validation is wired but OFF until the capstone turns it on");
  }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `flox activate -- ./mvnw -pl :document-codec -am test -DskipTests=false`
Expected: FAIL — `DocumentCodec` does not exist.

- [ ] **Step 5: Implement `DocumentCodec` (the single source)**

`osgi/foundation/document-codec/src/main/java/io/nxmatic/rke2lab/document/DocumentCodec.java`:
```java
package io.nxmatic.rke2lab.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The JSON (de)serialization + (capability) validation of {@code Document} payloads — the JSON
 * analogue of the manifests domain's {@code YamlMapper}. Written ONCE here; loaded per realm: the
 * host shades this jar flat (binding the host's flat jackson), and {@code doctor-core} nests it on
 * its Bundle-ClassPath ({@code -includeresource;lib:=true}, binding the bundle's jackson). No codec
 * type crosses the String-only world-gateway seam — each realm holds its own copy, exactly as
 * jackson is dual-loaded. Runtime schema validation is WIRED but OFF by default (the embedded
 * posture); the remote capstone flips it on via {@link #withValidation(boolean)}.
 */
public final class DocumentCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final boolean validationEnabled;

  public DocumentCodec() {
    this(false);
  }

  private DocumentCodec(boolean validationEnabled) {
    this.validationEnabled = validationEnabled;
  }

  /** The off→on switch the capstone flips; embedded keeps the default (off). */
  public DocumentCodec withValidation(boolean enabled) {
    return new DocumentCodec(enabled);
  }

  public String encode(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to encode Document payload", ex);
    }
  }

  public JsonNode decode(String payload) {
    try {
      return MAPPER.readTree(payload);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to decode Document payload", ex);
    }
  }

  /**
   * Validate a payload against the coordinate's schema. INERT while validation is off (embedded):
   * returns {@code true} without loading a schema. The capstone enables it; that path loads
   * {@code schema/<slug>.schema.json} from the classpath and runs networknt.
   */
  public boolean validate(String payload, String schemaSlug) {
    if (!validationEnabled) {
      return true;
    }
    throw new UnsupportedOperationException(
        "runtime validation is enabled only by the remote capstone (schema=" + schemaSlug + ")");
  }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `flox activate -- ./mvnw -pl :document-codec -am test -DskipTests=false`
Expected: PASS. Then verify the jar is plain: `unzip -p osgi/foundation/document-codec/target/document-codec-0.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep -i Bundle-SymbolicName` → expect NO output (a plain jar; if a `Bundle-SymbolicName` appears, fix per Step 1's note before continuing).

- [ ] **Step 7: Wire the host (flat) — `exec/seed-master/pom.xml`**

Add the dependency (managed version via the reactor):
```xml
<dependency>
  <groupId>io.nxmatic.rke2lab</groupId>
  <artifactId>document-codec</artifactId>
</dependency>
```
The host shade includes it flat by default (it is NOT in the staging shade-exclude set — it is never staged as a bundle). No shade config change needed.

- [ ] **Step 8: Wire OSGi (nested) — `doctor-core/pom.xml` + `bnd.bnd`**

In `doctor-core/pom.xml` add the same dependency. In `doctor-core/bnd.bnd` add the nesting (doctor-core has no `-includeresource` today — add one):
```
-includeresource: document-codec-*.jar;lib:=true
```
This puts the codec's classes on doctor-core's Bundle-ClassPath, bound to the jackson bundle doctor-core already imports. (doctor-core already imports `com.fasterxml.jackson.databind`, so the nested codec's jackson references resolve through doctor-core's existing wiring — no new Import-Package needed beyond what bnd computes.)

- [ ] **Step 9: Full reactor gate — prove the new pattern**

Run: `flox activate -- ./mvnw clean package -DskipTests=false -Pall-worlds -Dmaven.build.cache.enabled=false`
Expected: BUILD SUCCESS. Verify:
- `unzip -l exec/seed-master/target/seed-master-0.0.0-SNAPSHOT-exec.jar | grep 'io/nxmatic/rke2lab/document/DocumentCodec'` → present FLAT (host copy).
- `unzip -l exec/seed-master/target/seed-master-0.0.0-SNAPSHOT-exec.jar | grep 'META-INF/bundles/document-codec'` → ABSENT (not staged as an autonomous bundle).
- `unzip -p exec/seed-master/target/.../META-INF/bundles/doctor-core.jar | …` — the doctor-core staged bundle contains `document-codec-*.jar` on its Bundle-ClassPath (nested copy).
- gate summary: `duplicate-realm-class 0/0`, `realm-boundary 0/0` (the codec package is neither a seam nor a cross-realm collision).
- `EmbeddedBundlesBootTest` still green.

- [ ] **Step 10: Commit**

```bash
git add osgi/foundation/pom.xml osgi/foundation/document-codec/ osgi/domains/doctor/doctor-core/pom.xml osgi/domains/doctor/doctor-core/bnd.bnd exec/seed-master/pom.xml
git commit -m "feat(2d): DocumentCodec — one source in foundation, shaded flat host-side, nested OSGi-side"
```
(If the build proves the pattern, record it: a memory note `nesting-our-own-flat-module-into-a-bundle` — the first time we nest one of our own modules, not a third-party jar, into a Bundle-ClassPath. See the final review.)

---

## Task 3: The SCHEMA_CONCORD gate — enum + concord engine (zone-0c)

**Files:**
- Modify: `osgi/foundation/domain-annotations/.../StagingGate.java` (+ `SCHEMA_CONCORD`)
- Modify: `maven-embed-staging-ext/.../StagingGate.java` (mirror)
- Create: `maven-embed-staging-ext/.../CoordinateFieldUsage.java` (ASM: FIELD_* per class, Coordinate per class)
- Create: `maven-embed-staging-ext/.../SchemaConcord.java` (meta-schema validity + concord)
- Test: `maven-embed-staging-ext/.../SchemaConcordTest.java`
- Modify: `maven-embed-staging-ext/.../StagingExecutionStrategy.java` (wire into `enforceGates` + summary)

**Interfaces:**
- Consumes: `WorldGatewayCatalog` constant values + `Coordinate` slugs (read via ASM from the bundle classpath), networknt for meta-schema validity.
- Produces: `SchemaConcord(Path schemaDir, Map<String,Set<String>> fieldsByCoordinateSlug).violations()` → `List<String>` (one line per drift: missing-in-schema or missing-in-code or invalid-meta-schema). Reported under `gateLabel(SCHEMA_CONCORD)` = `schema-concord`.

- [ ] **Step 1: Add `SCHEMA_CONCORD` to both enums (with javadoc in the annotation module)**

In `osgi/foundation/domain-annotations/.../StagingGate.java`, add to the enum and the javadoc list:
```java
  /**
   * {@link #SCHEMA_CONCORD} — each Document coordinate has a JSON Schema
   * ({@code doctor-core/.../schema/<slug>.schema.json}) that (a) is itself valid against the
   * JSON-Schema meta-schema and (b) declares exactly the {@code WorldGatewayCatalog.FIELD_*}
   * properties the coordinate's producer/consumer code reads and writes. A field written but not
   * in the schema, or required by the schema but never written, is a concord violation.
   */
  SCHEMA_CONCORD
```
(Add `SCHEMA_CONCORD` as the last constant; mind the trailing comma/semicolon.)

In `maven-embed-staging-ext/.../StagingGate.java`, add `SCHEMA_CONCORD` to the mirror enum (before the `;` that precedes `fromName`).

- [ ] **Step 2: Write the failing test for the concord engine**

```java
// SchemaConcordTest.java
@Test
void aSchemaMissingAWrittenFieldIsAConcordViolation() throws Exception {
  // schema declares only "action"; code for readiness-verdict writes action + reason
  final Path dir = Files.createTempDirectory("schema-");
  Files.writeString(dir.resolve("readiness-verdict.schema.json"),
      "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
      + "\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"}}}");
  final SchemaConcord concord = new SchemaConcord(dir,
      Map.of("readiness-verdict", Set.of("action", "reason")));
  final List<String> v = concord.violations();
  assertTrue(v.stream().anyMatch(s -> s.contains("reason")),
      "a field the code writes but the schema omits is a concord violation");
}

@Test
void aValidSchemaWhoseFieldsMatchTheCodeIsClean() throws Exception {
  final Path dir = Files.createTempDirectory("schema-");
  Files.writeString(dir.resolve("readiness-verdict.schema.json"),
      "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
      + "\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"},"
      + "\"reason\":{\"type\":\"string\"}}}");
  final SchemaConcord concord = new SchemaConcord(dir,
      Map.of("readiness-verdict", Set.of("action", "reason")));
  assertTrue(concord.violations().isEmpty(), "matching fields + valid schema is clean");
}

@Test
void aMalformedSchemaIsAMetaSchemaViolation() throws Exception {
  final Path dir = Files.createTempDirectory("schema-");
  Files.writeString(dir.resolve("readiness-verdict.schema.json"),
      "{\"type\":12345}");  // type must be a string/array — meta-schema rejects
  final SchemaConcord concord = new SchemaConcord(dir,
      Map.of("readiness-verdict", Set.of()));
  assertTrue(concord.violations().stream().anyMatch(s -> s.toLowerCase().contains("meta")),
      "a schema invalid against the meta-schema is a violation");
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `flox activate -- ./mvnw -f maven-embed-staging-ext/pom.xml test -DskipTests=false -Dtest=SchemaConcordTest`
Expected: FAIL — `SchemaConcord` does not exist. (Extension may need to be disabled in `.mvn` first per Global Constraints — but building `-f maven-embed-staging-ext/pom.xml` builds the aggregator directly; if the active extension self-poisons, comment it out in `.mvn/extensions.xml` first.)

- [ ] **Step 4: Implement `SchemaConcord` (meta-schema + concord)**

```java
package io.nxmatic.rke2lab.maven.staging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The build-time SCHEMA_CONCORD guard: per Document coordinate, (a) the schema is valid against the
 * JSON-Schema meta-schema, and (b) the schema's declared {@code properties} == the set of
 * {@code WorldGatewayCatalog.FIELD_*} values the coordinate's code references. The field set is
 * discovered by {@link CoordinateFieldUsage} (ASM over the bundle classes) and passed in, so this
 * class stays a pure schema↔fields comparison.
 */
final class SchemaConcord {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path schemaDir;
  private final Map<String, Set<String>> fieldsByCoordinateSlug;

  SchemaConcord(Path schemaDir, Map<String, Set<String>> fieldsByCoordinateSlug) {
    this.schemaDir = schemaDir;
    this.fieldsByCoordinateSlug = fieldsByCoordinateSlug;
  }

  List<String> violations() {
    final List<String> lines = new ArrayList<>();
    for (Map.Entry<String, Set<String>> e : fieldsByCoordinateSlug.entrySet()) {
      final String slug = e.getKey();
      final Path schemaFile = schemaDir.resolve(slug + ".schema.json");
      if (!Files.isRegularFile(schemaFile)) {
        continue; // no schema yet for this coordinate — the WARN worklist entry, not a hard error
      }
      final JsonNode schemaNode = read(schemaFile);
      lines.addAll(metaSchemaViolations(slug, schemaNode));
      lines.addAll(concordViolations(slug, schemaNode, e.getValue()));
    }
    return lines;
  }

  private List<String> metaSchemaViolations(String slug, JsonNode schemaNode) {
    final JsonSchemaFactory factory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    final Set<com.networknt.schema.ValidationMessage> msgs =
        factory.getSchema(factory.getSchema(SpecVersion.VersionFlag.V202012.getId()).getSchemaNode())
            .validate(schemaNode);
    final List<String> lines = new ArrayList<>();
    for (var m : msgs) {
      lines.add(slug + ": meta-schema: " + m.getMessage());
    }
    return lines;
  }

  private List<String> concordViolations(String slug, JsonNode schemaNode, Set<String> codeFields) {
    final List<String> lines = new ArrayList<>();
    final Set<String> schemaProps = new LinkedHashSet<>();
    final JsonNode props = schemaNode.path("properties");
    props.fieldNames().forEachRemaining(schemaProps::add);
    for (String field : codeFields) {
      if (!schemaProps.contains(field)) {
        lines.add(slug + ": field written/read by code but absent from schema: " + field);
      }
    }
    for (String prop : schemaProps) {
      if (!codeFields.contains(prop)) {
        lines.add(slug + ": schema declares property never used by code: " + prop);
      }
    }
    return lines;
  }

  private static JsonNode read(Path file) {
    try {
      return MAPPER.readTree(Files.readString(file));
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read schema " + file, ex);
    }
  }
}
```
(If the networknt meta-schema bootstrap API differs at 1.5.6, adjust `metaSchemaViolations` to the version's idiom — the contract is: "validate `schemaNode` against the draft-2020-12 meta-schema, return messages". Keep the method's signature and the `slug + ": meta-schema: "` line prefix stable.)

- [ ] **Step 5: Run to verify the three tests pass**

Run: `flox activate -- ./mvnw -f maven-embed-staging-ext/pom.xml test -DskipTests=false -Dtest=SchemaConcordTest`
Expected: PASS (3/3).

- [ ] **Step 6: Implement `CoordinateFieldUsage` — the ASM field/coordinate discovery**

```java
package io.nxmatic.rke2lab.maven.staging;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Discovers, from bundle bytecode, which {@code WorldGatewayCatalog.FIELD_*} VALUES each
 * {@code Coordinate} slug's code references — the code side of the SCHEMA_CONCORD concord check.
 *
 * <p>Two passes: first read {@code WorldGatewayCatalog} to map each {@code FIELD_*} field name to
 * its inlined {@code ConstantValue} String (the wire key); and read {@code Coordinate} to map each
 * enum constant to its slug. Then, for each class, collect the {@code FIELD_*} GETSTATICs and the
 * {@code Coordinate.<X>} GETSTATICs it references; a class that names exactly one coordinate
 * contributes its fields to that coordinate's slug. (Class-granularity — sufficient for the 2C
 * reality where each producer/consumer is single-coordinate; see plan RISK NOTE.)
 */
final class CoordinateFieldUsage {

  private static final String CATALOG = "io/nxmatic/rke2lab/world/gateway/port/WorldGatewayCatalog";
  private static final String COORDINATE = "io/nxmatic/rke2lab/world/gateway/port/Coordinate";

  private final Map<String, String> fieldNameToValue = new LinkedHashMap<>(); // FIELD_X -> "scenarioId"
  private final Map<String, String> coordinateConstToSlug = new LinkedHashMap<>(); // READINESS_VERDICT -> "readiness-verdict"
  private final Map<String, Set<String>> fieldsBySlug = new LinkedHashMap<>();

  /** Index the catalog constant values and the coordinate slugs (call once with their classfiles). */
  void indexCatalog(byte[] catalogClass) {
    new ClassReader(catalogClass).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override
      public FieldVisitor visitField(int access, String name, String descriptor,
          String signature, Object value) {
        if (name.startsWith("FIELD_") && value instanceof String s) {
          fieldNameToValue.put(name, s);
        }
        return null;
      }
    }, ClassReader.SKIP_CODE);
  }

  /** Provide the Coordinate enum's constant→slug map (parsed from the enum source or a known map). */
  void indexCoordinate(Map<String, String> constToSlug) {
    coordinateConstToSlug.putAll(constToSlug);
  }

  /** Scan one bundle class: attribute its FIELD_* uses to the single Coordinate it names. */
  void scan(byte[] classfile) {
    final Set<String> fields = new LinkedHashSet<>();
    final Set<String> coords = new LinkedHashSet<>();
    new ClassReader(classfile).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override
      public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
        return new MethodVisitor(Opcodes.ASM9) {
          @Override
          public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETSTATIC && CATALOG.equals(owner) && name.startsWith("FIELD_")) {
              fields.add(name);
            }
            if (opcode == Opcodes.GETSTATIC && COORDINATE.equals(owner)) {
              coords.add(name);
            }
          }
        };
      }
    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    if (coords.size() != 1 || fields.isEmpty()) {
      return; // attribute only when a class names exactly one coordinate (class-granularity)
    }
    final String slug = coordinateConstToSlug.get(coords.iterator().next());
    if (slug == null) {
      return;
    }
    final Set<String> values = fieldsBySlug.computeIfAbsent(slug, k -> new LinkedHashSet<>());
    for (String f : fields) {
      final String v = fieldNameToValue.get(f);
      if (v != null) {
        values.add(v);
      }
    }
  }

  Map<String, Set<String>> fieldsByCoordinateSlug() {
    return fieldsBySlug;
  }
}
```

- [ ] **Step 7: Wire the gate into `StagingExecutionStrategy.enforceGates`**

In `enforceGates` (around line 148–290, after the `DuplicateRealmClass` block at line 275), build the field map from the staged bundle classes and add the gate. The `Coordinate` const→slug map is read once (parse from the staged `Coordinate.class` enum, or hardcode the 6 from `Coordinate` — but per identifier discipline, prefer reading the class). Then:
```java
    final CoordinateFieldUsage usage = new CoordinateFieldUsage();
    usage.indexCatalog(/* bytes of WorldGatewayCatalog.class from the world-gateway bundle */);
    usage.indexCoordinate(/* Coordinate const->slug, read from Coordinate.class */);
    for (ResolvedBundle b : closure.staged()) {
      for (ClassEntry c : b.classes()) {  // use the same class-iteration the realm gate uses
        usage.scan(c.bytes());
      }
    }
    final Path schemaDir = /* doctor-core staged bundle's schema/ dir, or the reactor resource dir */;
    final SchemaConcord schemaConcord = new SchemaConcord(schemaDir, usage.fieldsByCoordinateSlug());
    accumulate(counts, lines, doctorCoreBundle, governance, "schema-concord drift",
        schemaConcord.violations(), StagingGate.SCHEMA_CONCORD);
```
(Adapt the class-iteration + schema-dir resolution to the strategy's existing helpers — the realm-boundary block at lines 208–234 shows how it iterates `c.binaryName()`/`c.bytes()`. The schema files ship as doctor-core resources; resolve their on-disk dir from the doctor-core module's `target/classes/schema` during the build, or from the staged bundle jar. Match whatever the strategy already has for locating bundle content.)

- [ ] **Step 8: Add `schema-concord` to `gateLabel`** (the summary label switch — find `gateLabel(StagingGate)` and add the `SCHEMA_CONCORD -> "schema-concord"` case).

- [ ] **Step 9: Two-phase rebuild + reactor**

```bash
# comment out the staging-extension block in .mvn/extensions.xml
flox activate -- ./mvnw -f maven-embed-staging-ext/pom.xml clean install -DskipTests=false
# uncomment the block
flox activate -- ./mvnw clean package -DskipTests=false -Pall-worlds -Dmaven.build.cache.enabled=false
```
Expected: BUILD SUCCESS. Gate summary shows `schema-concord: 0 error, 0 warn` (no schema files yet → no coordinate has a schema → `violations()` skips them → clean). The enum + engine are in place, dormant.

- [ ] **Step 10: Commit**

```bash
git add osgi/foundation/domain-annotations/ maven-embed-staging-ext/
git commit -m "feat(2d): SCHEMA_CONCORD gate — enum (both mirrors) + meta-schema + concord engine (no schemas yet)"
```

---

## Tasks 4–9: one coordinate at a time (zones 1–6)

Each task is identical in SHAPE; only the slug, the FIELD_* set, and the producer/consumer differ. Order (simplest first, per spec §7): **4=readiness-verdict, 5=intervention-request, 6=intervention, 7=readiness-checkpoint, 8=consultation, 9=visit.**

For each coordinate the task is:

- [ ] **Step 1: Govern doctor-core WARN for SCHEMA_CONCORD** (only in Task 4, the first; the `@GovernedBy(SCHEMA_CONCORD, WARN)` stays until the flip). In `doctor-core/.../package-info.java` add `@GovernedBy(value = StagingGate.SCHEMA_CONCORD, level = EnforcementLevel.WARN)`.

- [ ] **Step 2: Determine the coordinate's field set from the code** (read the producer + consumer named in spec §3; list the `WorldGatewayCatalog.FIELD_*` they reference for this coordinate). For readiness-verdict: producer `DefaultReadinessAuthority.assess` + `DefaultInterventionIntake` (error path); consumer host stages read `FIELD_ACTION`. Verified fields include `FIELD_ACTION` ("action"), `FIELD_REASON` ("reason") — confirm the full set by reading the classes before writing the schema.

- [ ] **Step 3: Write `<slug>.schema.json`** in `doctor-core/src/main/resources/schema/` declaring exactly those properties (draft 2020-12, `"type":"object"`, `properties` keyed on the FIELD_* VALUES, `required` for the always-written ones).

- [ ] **Step 4: Write the round-trip test** (produce a payload via the real producer or a faithful builder → `DocumentCodec.decode` → assert the schema's properties are all present → consume via the real reader). Place in doctor-core-test.

- [ ] **Step 5: Run the coordinate test + the gate**, confirm `schema-concord` worklist drops by one and stays WARN (green build). Command: the two-phase reactor build from Task 3 Step 9.

- [ ] **Step 6: Commit** `feat(2d): <slug> schema + round-trip — SCHEMA_CONCORD worklist N→N-1`.

(Each of Tasks 4–9 instantiates the six steps with its own slug/fields. When executing, read the producer/consumer first — do NOT guess the field set; the gate will catch a guess as a concord drift, which is the point.)

---

## Task 10: The flip (final zone)

**Files:**
- Modify: `doctor-core/.../package-info.java` (remove `@GovernedBy(SCHEMA_CONCORD, WARN)`)

- [ ] **Step 1: Confirm all six coordinates concord at WARN** — run the full reactor gate; `schema-concord: 0 error, 0 warn` with all six schemas present and matching.

- [ ] **Step 2: Remove the `@GovernedBy(SCHEMA_CONCORD, WARN)` line** from doctor-core's `package-info.java` (returns to the ERROR default).

- [ ] **Step 3: Run the full reactor gate** — `flox activate -- ./mvnw clean package -DskipTests=false -Pall-worlds -Dmaven.build.cache.enabled=false`. Expected: BUILD SUCCESS with `schema-concord: 0 error, 0 warn` at ERROR level. A drift would now fail the build (the lock working).

- [ ] **Step 4: Commit** `feat(2d): flip SCHEMA_CONCORD WARN→ERROR — the Document contract is build-enforced`.

- [ ] **Step 5: Update the doc cross-ref backlog** — fix `world-gateway-2b-consult-path-spec.adoc:224` ("REALM_BOUNDARY → ERROR flip — increment 2D" → moved to 2C zone-5) in this commit or a doc-close commit.

---

## Self-Review

- **Spec coverage:** zone-0 (Tasks 1–3: networknt, codec, gate) ✓; zones 1–6 (Tasks 4–9, by coordinate) ✓; flip (Task 10) ✓; the five reconciliation decisions: 5.1 anchor-by-coordinate (no DomainDagMapper) ✓ (schemas by slug, gate links via Coordinate+FIELD_*); 5.2 per-realm codec ✓ (OSGi codec Task 2; host instance + 19-site migration explicitly deferred to backlog); 5.3 networknt ✓ (Task 1); 5.4 build-gate load-bearing + codec off ✓ (Task 2 inert validate + Task 3 gate); 5.5 schemas OSGi-side ✓ (doctor-core resources).
- **Scope boundary honored:** no carrier/streaming change; no capstone work; host-codec dispersed-site migration deferred.
- **Known risk surfaced:** part-(b) concord engine (Task 3) is front-loaded and proven on one coordinate (Task 4) before scaling; class-granularity assumption documented.
- **Open detail to resolve at execution:** the exact networknt 1.5.6 meta-schema bootstrap API (Task 3 Step 4) and the schema-dir resolution inside `StagingExecutionStrategy` (Task 3 Step 7) — both flagged inline, contracts stated.
