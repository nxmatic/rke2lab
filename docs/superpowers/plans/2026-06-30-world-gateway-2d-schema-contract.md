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

## Task 2: The per-realm DocumentCodec (zone-0b) — BOTH realms, wired, validation OFF

**Files:**
- Create: `osgi/domains/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/DocumentCodec.java` (OSGi `@Component`)
- Create: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/DocumentCodec.java` (host plain instance)
- Test: `osgi/domains/doctor/doctor-core-test/.../DocumentCodecTest.java` (OSGi side)
- Test: `exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/DocumentCodecTest.java` (host side)

**Interfaces:**
- Produces (BOTH realms, identical shape): `DocumentCodec` with `String encode(JsonNode)`, `JsonNode decode(String)`, and `boolean validate(String payload, String schemaSlug)` that is INERT (returns `true`) when the validation flag is off.

**Why two classes, not one (decision 5.2):** the codec uses jackson, and since the realm-library isolation (commit `ae46278b`) jackson is loaded per realm — a flat copy host-side, a bundle copy OSGi-side. A shared codec class is therefore impossible: it cannot live in the `world-gateway` seam (`type=seam`, String-only, forbidden from exposing a jackson type), the OSGi copy binds to the bundle's jackson (`@Component` in doctor-core), and the host copy binds to the flat jackson (plain instance in seed-master). The two classes carry identical LOGIC but are bound to different `ObjectMapper` classes — the duplication is the realm boundary made concrete (the same reason jackson itself is dual-staged), NOT a DRY violation. The seam stays String-only; no jackson type crosses it.

- [ ] **Step 1: Write the failing test — codec round-trips and validation is inert by default**

```java
// DocumentCodecTest.java
@Test
void encodeDecodeRoundTripsAndValidationIsInertByDefault() {
  final DocumentCodec codec = new DocumentCodec();
  final String json = codec.encode(codec.decode("{\"action\":\"hold\"}"));
  assertTrue(json.contains("\"action\""));
  // validation OFF in embedded: a payload that does NOT match any schema still passes
  assertTrue(codec.validate("{\"unexpected\":1}", "readiness-verdict"),
      "validation is wired but OFF in embedded — the OSGi reader is the implicit validator");
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `flox activate -- ./mvnw -pl :doctor-core-test -am test -DskipTests=false -Dtest=DocumentCodecTest -Pall-worlds`
Expected: FAIL — `DocumentCodec` does not exist.

- [ ] **Step 3: Implement `DocumentCodec` (twin of `YamlMapper`)**

```java
package io.nxmatic.rke2lab.doctor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.osgi.service.component.annotations.Component;

/**
 * The JSON (de)serialization + (capability) validation point for Document payloads in the OSGi
 * world — the JSON twin of the manifests domain's {@code YamlMapper}. A single {@code @Component}
 * so every payload build/parse shares one configured {@code ObjectMapper}; a plain {@code new
 * DocumentCodec()} is equally valid (tests). Runtime schema validation is WIRED but OFF in the
 * embedded build (the OSGi reader that parses a payload is the implicit validator); the remote
 * capstone turns it on via {@link #withValidation(boolean)}.
 */
@Component(service = DocumentCodec.class)
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
   * Validate a payload against the coordinate's schema. INERT when validation is off (embedded):
   * returns {@code true} without loading a schema. The capstone enables it; that path loads
   * {@code schema/<slug>.schema.json} from the classpath and runs networknt.
   */
  public boolean validate(String payload, String schemaSlug) {
    if (!validationEnabled) {
      return true;
    }
    // capstone-only path; not exercised in embedded. networknt validation lands here.
    throw new UnsupportedOperationException(
        "runtime validation is enabled only by the remote capstone (schema=" + schemaSlug + ")");
  }
}
```

- [ ] **Step 4: Run the OSGi-side test to verify it passes**

Run: `flox activate -- ./mvnw -pl :doctor-core-test -am test -DskipTests=false -Dtest=DocumentCodecTest -Pall-worlds`
Expected: PASS.

- [ ] **Step 5: Write the host-side failing test** (`exec/seed-master/.../controlplane/DocumentCodecTest.java`)

Same three assertions as Step 1, against `io.nxmatic.rke2lab.controlplane.DocumentCodec`:
```java
// host DocumentCodecTest.java — same behaviour, host realm
@Test
void encodeDecodeRoundTripsAndValidationIsInertByDefault() {
  final DocumentCodec codec = new DocumentCodec();
  final String json = codec.encode(codec.decode("{\"action\":\"hold\"}"));
  assertTrue(json.contains("\"action\""));
  assertTrue(codec.validate("{\"unexpected\":1}", "readiness-verdict"),
      "validation is wired but OFF in embedded");
}
```
Run: `flox activate -- ./mvnw -pl :seed-master -am test-compile -DskipTests` then the focused test once the class exists — expect FAIL (class missing) first.

- [ ] **Step 6: Implement the host `DocumentCodec`** — byte-identical logic to the OSGi one, WITHOUT the `@Component` annotation (the host has no DS), in package `io.nxmatic.rke2lab.controlplane`. Same `encode`/`decode`/`validate`/`withValidation`; the javadoc names it the host-realm twin of the OSGi `DocumentCodec`, bound to the host's flat jackson. Do NOT factor the two into a shared class — they are realm-bound (see Task 2 header). Do NOT migrate the ~19 dispersed payload-construction sites onto the codec — that is the §8 follow-up (`document-codec-instance-in-2d-backlog`), not a 2D blocker; this task only introduces the codec on each realm.

- [ ] **Step 7: Run the host-side test** — focused, expect PASS.

- [ ] **Step 8: Commit**

```bash
git add osgi/domains/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/DocumentCodec.java osgi/domains/doctor/doctor-core-test/ exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/DocumentCodec.java exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/DocumentCodecTest.java
git commit -m "feat(2d): per-realm DocumentCodec — OSGi @Component + host instance, validation wired-but-off"
```

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
