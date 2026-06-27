# World Exchange 2B — Consult Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the host↔OSGi *consult path* cross as a Document — the host stops producing `Observation`/`Symptom`, stops calling `consult(Symptom,Observation)`, and stops rendering the diagnosis from a `RemediationPlan`; the doctor's reasoning and AsciiDoc rendering move OSGi-side.

**Architecture:** Add a `consult(Document checkpoint) → Document consultation` verb to the `ConsultingService` seam (the Document twin of 2A's `assess`). The OSGi authority (`Generalist`) parses the checkpoint, routes/reasons as today, and returns a consultation Document carrying only strings (`narration`, `diagnosisAdoc`, `checkpointId`). The host builds the checkpoint from host-native facts, logs the narration, and inserts `diagnosisAdoc` verbatim into its jGiven runbook shell. By zone: zone-0 (seam + the `DoctorGraph`→`ConsultationDag` rename) first, then zone-1 (systemd-adapter), then zone-2 (cluster).

**Tech Stack:** Java 25, OSGi (embedded Felix, DS `@Component`), bnd, Jackson `JsonNode`, jGiven AsciiDoc report, JUnit 5, the `exchange-port` seam from 2A.

## Global Constraints

- *Spec of record:* `docs/architecture/osgi/world-exchange-2b-consult-path-spec.adoc`. Read it first.
- *No doctor type on the host consult path.* After this plan, `SystemdAdapterStage`, `ClusterReadinessStage`, both probes (`Live`/`Simulated`), `SeedSystemdAdapterEndpointGate`, `RunbookRenderer`, and the two jGiven consult scenarios import ZERO `doctor.records` types.
- *consult is a DISTINCT verb*, twin of `assess` — NOT folded into `assess`. `ReadinessAuthority` (verdict) and `ConsultingService` (consultation) stay separate seams.
- *The host transports strings.* The consultation Document carries only `String` fields. `RemediationPlan`/`ReferralReply`/`ConsultationReport` NEVER cross to the host; they stay OSGi-side where `ConsultationDag` produces and renders them.
- *AsciiDoc is markup, not rendered HTML.* `diagnosisAdoc` is a `StringBuilder` of AsciiDoc text. Add NO asciidoctor / jruby / graphviz dependency.
- *Identifiers via `ExchangeCatalog`*, never literal strings (the `clusterApi`-bug discipline). New constants: the `consultation` coordinate and the `narration`/`diagnosisAdoc`/`symptomKind`/`summary`/`details` field names.
- *`ConsultationReport` is NOT deleted* — it stays alive for the reconstruction path (2C) and its OSGi-side tests. 2B only stops the host *consult path* from building it.
- *The two reconstruction verbs* (`recordForCurrentPatient`, `reviewOpenProblems`) stay on `ConsultingService` untouched (zone-3 / 2C).
- *Build & verify:* never `mvn install` project artifacts; build with `-am`; tests run with `-DskipTests=false`. **seed-master tests run via `package -Pall-worlds`, never bare `test`** (the staging `stage-embedded-bundles` copy is bound to generate-resources and needs shade in the mojo list). Cache off with `-Dmaven.build.cache.skipCache=true`. doctor in-container tests run via bare `test` on their module.
- *Green per zone:* zone-0 is build-green + doctor-in-container-green but the host `REALM_BOUNDARY` worklist does NOT shrink yet (host still calls old verbs). zones 1 & 2 shrink the worklist. This is expected — note it, don't treat the flat worklist as a regression after zone-0.

---

## Task 1 (zone-0a): the typed exchange vocabulary (enums) + the `consult(Document)` seam verb

> **Status note:** commit `864ec8d8` already landed the first half of this task — it added the
> `consult(Document)` verb to `ConsultingService`, the `exchange-port`→`doctor-port` pom dependency,
> and the consultation/checkpoint field constants to `ExchangeCatalog`. That commit STAYS. This task
> as revised adds the second half (a user-requested refinement): lift the catalog's *closed value
> domains* into typed enums, because `ExchangeCatalog` had become a flat fourre-tout mixing three
> natures — coordinates (a closed set), enumerated field values (closed sets), and payload field
> keys (the schema). Coordinates/actions/symptom-kinds are closed domains → enums; only the schema
> keys stay catalog constants. This also supplies the **missing** symptom-kind vocabulary the host
> needs in zone-1/2 once it drops `doctor.records.Symptom`.

**Files:**
- Create: `osgi/exchange/exchange-port/src/main/java/io/nxmatic/rke2lab/exchange/port/Coordinate.java`
- Create: `osgi/exchange/exchange-port/src/main/java/io/nxmatic/rke2lab/exchange/port/Action.java`
- Create: `osgi/exchange/exchange-port/src/main/java/io/nxmatic/rke2lab/exchange/port/SymptomKind.java`
- Modify: `osgi/exchange/exchange-port/src/main/java/io/nxmatic/rke2lab/exchange/port/ExchangeCatalog.java` (slim to schema keys)
- Modify: `osgi/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/internal/DefaultReadinessAuthority.java` (call sites)
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterStage.java` (call sites)
- Test (create): `osgi/exchange/exchange-port/src/test/java/io/nxmatic/rke2lab/exchange/port/ExchangeVocabularyTest.java`
- Test (modify): `osgi/exchange/exchange-port/src/test/java/io/nxmatic/rke2lab/exchange/port/DocumentTest.java`
- Test (modify): `osgi/doctor/doctor-core/src/test/java/io/nxmatic/rke2lab/doctor/ReadinessAuthorityTest.java`
- Test (modify): `exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterVerdictTest.java`

**Interfaces:**
- Consumes: `Document` (from 2A). `Document` STAYS the neutral envelope `(String domain, String coordinate, JsonNode payload)` — do NOT type the coordinate into it; the envelope must not couple to the doctor coordinate vocabulary.
- Produces: FOUR enums in `io.nxmatic.rke2lab.exchange.port`, each with `String slug()` + `static Optional<E> parse(String)` (slug-strict), mirroring the codebase enum idiom (`Severity.parse`, `Symptom.parse`, `Checkpoint.slug`):
  - `Domain{ DOCTOR("doctor") }` — the document owner. (One value today; forward-extensible if other ports exchange Documents. This is the slot `Document.domain()` carries, NOT the host's `InfraDomainCatalog` provisioning axis nor `ManifestDomainCatalog` — a different axis AND a different layer; the seam leaf cannot reference the host.)
  - `Coordinate{ READINESS_CHECKPOINT("readiness-checkpoint"), READINESS_VERDICT("readiness-verdict"), CONSULTATION("consultation") }`
  - `Action{ STOP("stop"), CONTINUE_DEGRADED("continue-degraded") }`
  - `SymptomKind{ CONNECTION_REFUSED("connection-refused"), TIMEOUT("timeout"), KUBECONFIG_MISSING("kubeconfig-missing"), API_NOT_READY("api-not-ready"), CONTROLLER_NOT_READY("controller-not-ready") }` — the five slugs of `doctor.records.Symptom`, host-flat. (`Generalist` maps `SymptomKind`→`Symptom` in Task 2; the host produces `SymptomKind.X.slug()` in Tasks 4-5.)
- `ExchangeCatalog` (slimmed) keeps ONLY the `FIELD_*` payload schema keys. REMOVES `DOMAIN_DOCTOR` (→ `Domain.DOCTOR`), `READINESS_CHECKPOINT`/`READINESS_VERDICT`/`CONSULTATION` (→ `Coordinate`), `ACTION_STOP`/`ACTION_CONTINUE_DEGRADED` (→ `Action`). The `FIELD_*` keys STAY constants — the real "which fields per coordinate" typing is the per-coordinate JSON Schema in 2D; a flat `Field` enum would re-merge checkpoint+verdict+consultation fields (the fourre-tout one level down) and pre-empt 2D. `consult(Document)` already on `ConsultingService` from `864ec8d8`.

> **Half-3 (this revision):** commits `864ec8d8` (verb + dep + field constants) and `a892c8ee`
> (Coordinate/Action/SymptomKind enums + 2A call-site migration) already landed. Half-3 adds the
> `Domain` enum and removes the last non-field constant `DOMAIN_DOCTOR` from `ExchangeCatalog`, so
> the catalog is purely the `FIELD_*` schema keys and EVERY closed value domain in the seam is a typed
> enum. Six `DOMAIN_DOCTOR` call sites migrate to `Domain.DOCTOR.slug()`: `ExchangeCatalog` (decl),
> `DocumentTest` (×3 incl. the canonical-string assertion → move to `ExchangeVocabularyTest`),
> `ReadinessAuthorityTest`, `DefaultReadinessAuthority`, `SystemdAdapterVerdictTest`,
> `SystemdAdapterStage`. `Document` STAYS the neutral `(String domain, String coordinate, JsonNode)`
> — call sites write `Domain.DOCTOR.slug()` into the slot; the envelope is not coupled to the enum.

- [ ] **Step 1: Write the failing test** — create `ExchangeVocabularyTest` pinning each enum's slug + a parse round-trip, mirroring `DocumentTest`'s canonical-strings discipline. Example for one enum (do all three):

```java
@Test
void coordinateSlugsArePinnedAndRoundTrip() {
  assertEquals("readiness-checkpoint", Coordinate.READINESS_CHECKPOINT.slug());
  assertEquals("readiness-verdict", Coordinate.READINESS_VERDICT.slug());
  assertEquals("consultation", Coordinate.CONSULTATION.slug());
  assertEquals(Optional.of(Coordinate.CONSULTATION), Coordinate.parse("consultation"));
  assertEquals(Optional.empty(), Coordinate.parse("nope"));
}
```

- [ ] **Step 2: Run it, verify it fails** — `flox activate -- ./mvnw -pl :exchange-port -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ExchangeVocabularyTest`. Expected: FAIL (enums undefined / won't compile).

- [ ] **Step 3: Create the three enums.** Each is a `public enum` in `io.nxmatic.rke2lab.exchange.port` with a private `final String slug`, a slug-arg constructor, `public String slug()`, and `public static Optional<E> parse(String slug)` that returns empty on null/blank/unknown and matches on `slug` only. Match the javadoc voice of `Document`/`ReadinessAuthority`. `Coordinate`:

```java
package io.nxmatic.rke2lab.exchange.port;

import java.util.Optional;

/**
 * The closed set of document coordinates — the document type and schema key a {@link Document}
 * carries. Lifted from loose strings so a call site cannot name a coordinate that does not exist
 * (the {@code clusterApi}-bug discipline). {@code slug()} is the wire value placed in {@link
 * Document#coordinate()}; the envelope stays neutral and never holds this enum.
 */
public enum Coordinate {
  READINESS_CHECKPOINT("readiness-checkpoint"),
  READINESS_VERDICT("readiness-verdict"),
  CONSULTATION("consultation");

  private final String slug;

  Coordinate(String slug) {
    this.slug = slug;
  }

  public String slug() {
    return slug;
  }

  /** Resolves a wire coordinate; null/blank/unknown yields empty. */
  public static Optional<Coordinate> parse(String slug) {
    if (slug == null || slug.isBlank()) {
      return Optional.empty();
    }
    for (Coordinate coordinate : values()) {
      if (coordinate.slug.equals(slug)) {
        return Optional.of(coordinate);
      }
    }
    return Optional.empty();
  }
}
```

`Action` and `SymptomKind` are byte-for-byte the same shape (constants + slug + parse) — UNIFORM, no variants. `SymptomKind`'s javadoc notes it is the host-flat twin of the doctor's internal symptom enum (OSGi owns and maps back).

- [ ] **Step 4: Slim `ExchangeCatalog`.** Remove the lifted constants and their javadoc. (Half-2 removed the five coordinate/action constants but kept `DOMAIN_DOCTOR`; **half-3 (Step 7) removes `DOMAIN_DOCTOR` too** → `Domain.DOCTOR`, leaving ONLY the `FIELD_*` schema keys.) Update the class javadoc: it is now the single source for *payload field keys* (the schema); closed value domains (domain, coordinates, actions, symptom kinds) are the typed enums in this package.

- [ ] **Step 5: Migrate the call sites.** `DefaultReadinessAuthority`: `verdict.put(FIELD_ACTION, stop ? Action.STOP.slug() : Action.CONTINUE_DEGRADED.slug())` and `new Document(DOMAIN_DOCTOR, Coordinate.READINESS_VERDICT.slug(), verdict)`; add the `Action`/`Coordinate` imports. `SystemdAdapterStage`: `if (Action.STOP.slug().equals(action))` and `new Document(DOMAIN_DOCTOR, Coordinate.READINESS_CHECKPOINT.slug(), payload)` in `checkpointDocument`; add imports. Then the three test files: `DocumentTest` (the `READINESS_VERDICT`/`ACTION_STOP` references → `Coordinate`/`Action`, and drop the removed-constant assertions, which now live in `ExchangeVocabularyTest`), `ReadinessAuthorityTest`, `SystemdAdapterVerdictTest`. Grep `ExchangeCatalog.ACTION_`, `ExchangeCatalog.READINESS_`, `ExchangeCatalog.CONSULTATION` across `osgi exec` (exclude `/target/`) — expected ZERO after.

- [ ] **Step 6: Verify green.** exchange-port + doctor-core via bare `test`; seed-master MUST go through `package -Pall-worlds`:
  - `flox activate -- ./mvnw -pl :exchange-port -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ExchangeVocabularyTest,DocumentTest`
  - `flox activate -- ./mvnw -pl :doctor-core -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ReadinessAuthorityTest`
  - `flox activate -- ./mvnw -pl :seed-master -am package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=SystemdAdapterVerdictTest`
  All PASS.

- [ ] **Step 7: Commit** — `git commit -m "refactor(exchange): lift coordinate/action/symptom-kind closed domains into seam enums"` (the verb + dep already shipped in `864ec8d8`; this commit is the vocabulary typing). End with the `Co-Authored-By` trailer. *(Shipped: `a892c8ee`.)*

### Step 8 (half-3): the `Domain` enum — the last closed domain leaves the catalog

Add `Domain` so EVERY closed value domain in the seam is a typed enum and `ExchangeCatalog` is purely the `FIELD_*` schema keys.

- [ ] **8a: RED** — in `ExchangeVocabularyTest`, add `domainSlugsArePinnedAndRoundTrip()`:

```java
@Test
void domainSlugsArePinnedAndRoundTrip() {
  assertEquals("doctor", Domain.DOCTOR.slug());
  assertEquals(Optional.of(Domain.DOCTOR), Domain.parse("doctor"));
  assertEquals(Optional.empty(), Domain.parse("nope"));
}
```

Run `flox activate -- ./mvnw -pl :exchange-port -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ExchangeVocabularyTest` → FAIL (Domain undefined).

- [ ] **8b: Create `Domain`** in `io.nxmatic.rke2lab.exchange.port`, byte-for-byte the same shape as `Coordinate`/`Action`/`SymptomKind` (single value `DOCTOR("doctor")`). Javadoc: the document owner — the value carried in `Document.domain()`; note it is the exchange's own owner axis, NOT the host's `InfraDomainCatalog` provisioning domains nor `ManifestDomainCatalog` (different axis and layer; the seam leaf must not depend on the host).

- [ ] **8c: Remove `DOMAIN_DOCTOR`** from `ExchangeCatalog` (decl + javadoc). The catalog now holds ONLY `FIELD_*`. Update its class javadoc to say so: the single source for payload field keys; all closed value domains (domain, coordinate, action, symptom-kind) are typed enums in this package.

- [ ] **8d: Migrate the 6 `DOMAIN_DOCTOR` call sites** to `Domain.DOCTOR.slug()`: `DefaultReadinessAuthority` (the `assess` verdict Document), `SystemdAdapterStage` (`checkpointDocument`), `DocumentTest` (the two `new Document(...)`/`doc.domain()` uses; the `assertEquals("doctor", ExchangeCatalog.DOMAIN_DOCTOR)` line is DELETED — `Domain`'s canonical-string assertion now lives in `ExchangeVocabularyTest`), `ReadinessAuthorityTest`, `SystemdAdapterVerdictTest`. Add the `Domain` import where needed. Grep `DOMAIN_DOCTOR` across `osgi exec` (exclude `/target/`) → ZERO after.

- [ ] **8e: Verify green** — same three commands as Step 6 (exchange-port `ExchangeVocabularyTest,DocumentTest`; doctor-core `ReadinessAuthorityTest`; seed-master `package -Pall-worlds -Dtest=SystemdAdapterVerdictTest`). All PASS.

- [ ] **8f: Commit** — `git commit -m "refactor(exchange): lift the document-owner domain into a seam enum; catalog is now only schema field keys"`, `Co-Authored-By` trailer.

---

## Task 2 (zone-0b): `Generalist` implements `consult(Document)`, rendering narration + diagnosisAdoc

**Files:**
- Modify: `osgi/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/internal/Generalist.java`
- Test: `osgi/doctor/doctor-core-test/src/main/java/io/nxmatic/rke2lab/doctor/` — a new `GeneralistConsultDocumentTest` (the doctor-core-test module runs in-container; follow the existing `GeneralistRecordRetrievalTest` shape).

**Interfaces:**
- Consumes: `ConsultingService.consult(Document)` (Task 1), the `SymptomKind` seam enum (Task 1), the existing internal `consult(Symptom,Observation)→RemediationPlan` + `consultedLine`/`cohortFinding`, and the `diagnosisBlock` logic (moved in from `RunbookRenderer` — copy its body now, the host loses it in Task 6).
- Produces: a `consult(Document)` impl that returns a `consultation` Document with `narration` + `diagnosisAdoc` + echoed `scenarioId`. Plus a private `SymptomKind`→`Symptom` mapping (an exhaustive `switch` — OSGi owns `Symptom`, the seam carries only the host-flat `SymptomKind`).

- [ ] **Step 1: Write the failing test** — build a checkpoint Document (scenarioId + failed + `symptomKind = SymptomKind.CONNECTION_REFUSED.slug()` + summary + details), call the assembled `Generalist`'s `consult(Document)`, assert the returned Document's `coordinate()` equals `Coordinate.CONSULTATION.slug()`, its `narration` field is non-empty, and its `diagnosisAdoc` field contains `"⚕ Diagnosis:"`.

- [ ] **Step 2: Run it, verify it fails** — `flox activate -- ./mvnw -pl :doctor-core-test -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=GeneralistConsultDocumentTest`. Expected: FAIL (method not implemented / returns nothing).

- [ ] **Step 3: Implement `consult(Document)` in `Generalist`:**

```java
@Override
public Document consult(Document checkpoint) {
  final var payload = checkpoint.payload();
  final SymptomKind kind =
      SymptomKind.parse(payload.path(ExchangeCatalog.FIELD_SYMPTOM_KIND).asText()).orElseThrow();
  final Symptom symptom = toSymptom(kind);          // exhaustive switch, OSGi owns Symptom
  final Observation observation = observationFrom(payload); // summary/details → Observation, OSGi-side
  final RemediationPlan plan = consult(symptom, observation);  // the existing record-typed internal path
  final ObjectNode out = Document.newPayload();  // the seam owns payload construction (no per-component ObjectMapper)
  out.put(ExchangeCatalog.FIELD_SCENARIO_ID, payload.path(ExchangeCatalog.FIELD_SCENARIO_ID).asText());
  out.put(ExchangeCatalog.FIELD_NARRATION, narrationLine(symptom)); // was consultedLine + cohortFinding, joined
  out.put(ExchangeCatalog.FIELD_DIAGNOSIS_ADOC, diagnosisBlock(plan)); // moved in from RunbookRenderer
  return new Document(Domain.DOCTOR.slug(), Coordinate.CONSULTATION.slug(), out);
}

private static Symptom toSymptom(SymptomKind kind) {
  return switch (kind) {
    case CONNECTION_REFUSED -> Symptom.CONNECTION_REFUSED;
    case TIMEOUT -> Symptom.TIMEOUT;
    case KUBECONFIG_MISSING -> Symptom.KUBECONFIG_MISSING;
    case API_NOT_READY -> Symptom.API_NOT_READY;
    case CONTROLLER_NOT_READY -> Symptom.CONTROLLER_NOT_READY;
  };
}
```

Add the private helpers: `observationFrom(JsonNode)` (rebuild the `Observation` OSGi-side from the checkpoint's summary/details), `narrationLine(Symptom)` (join `consultedLine` + `cohortFinding`), and `diagnosisBlock(RemediationPlan)` (copy verbatim from `RunbookRenderer.diagnosisBlock` — the `⚕/🔬/℞` AsciiDoc StringBuilder). The `toSymptom` switch has NO `default` — adding a `Symptom`/`SymptomKind` value later forces this site to update (the anti-drift filet). doctor-core's pom already deps `exchange-port`.

- [ ] **Step 4: Run the test, verify it passes** — same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(doctor): Generalist.consult(Document) renders narration + diagnosis AsciiDoc OSGi-side"` *(shipped: `fc0e441e`)*.

### Step 6 (half-2): the seam owns payload construction — no per-component `ObjectMapper`

User-flagged: three components (`DefaultReadinessAuthority`, `SystemdAdapterStage`, `Generalist`) each held `new ObjectMapper()` only to call `createObjectNode()` — a dispersed concern. The seam owns the `Document` envelope, so it owns the payload's construction. Decision: a STATIC factory `Document.newPayload()` (stateless → uniform with the seam's `Coordinate/Action/SymptomKind.parse()` statics; the repo rule allows static for factory methods). The instance twin (`@Component DocumentCodec`, the JSON twin of manifests' `YamlMapper`) is deferred to 2D when payloads carry schema + ordering config — see the `document-codec-instance-in-2d-backlog` memory.

- [ ] **6a:** Add to `osgi/exchange/exchange-port/.../Document.java`: `public static ObjectNode newPayload()` → `JsonNodeFactory.instance.objectNode()` (no `ObjectMapper`; distinct name from the instance accessor `payload()`). *(Done.)*

- [ ] **6b:** Migrate the THREE `new ObjectMapper()` → `createObjectNode()` sites to `Document.newPayload()` and delete each `private final ObjectMapper mapper` field + the `ObjectMapper` import: `Generalist` (`out`), `DefaultReadinessAuthority` (`verdict`), `SystemdAdapterStage` (`checkpointDocument`'s `payload`). Uniformity: ALL three, not one — no half-migration. Grep `new ObjectMapper()` across `osgi exec` main sources (excl `/target/`, excl manifests' `YamlMapper`/`createYamlScalarSerializer` which are YAML config, legitimately their own) → only the now-removed exchange sites disappear.

- [ ] **6c: Verify green** — exchange-port `test`; doctor-core `ReadinessAuthorityTest` + doctor-core-test `GeneralistConsultDocumentTest`; seed-master `package -Pall-worlds -Dtest=SystemdAdapterVerdictTest`. All PASS.

- [ ] **6d: Commit** — `git commit -m "refactor(exchange): the seam owns Document payload construction (Document.newPayload), drop per-component ObjectMapper"`, `Co-Authored-By` trailer.

---

## Task 3 (zone-0c): rename `DoctorGraph` → `ConsultationDag`

**Files:**
- Rename: `osgi/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/internal/DoctorGraph.java` → `ConsultationDag.java`
- Modify: `osgi/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/internal/DefaultHealthSystem.java` (the `assemble` call site)
- Modify: `osgi/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/bnd.bnd` (prose reference, line ~3)
- Modify: `osgi/doctor/doctor-core-test/src/main/java/io/nxmatic/rke2lab/doctor/HealthSystemTest.java` (javadoc + `DoctorGraph.assemble` call)
- Modify: `osgi/doctor/doctor-core-test/src/main/java/io/nxmatic/rke2lab/doctor/ExactRosterDoctor.java` (`DoctorGraph.assemble` call)
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/resources/ResourceCreationPipeline.java` (the 1 host reference)

**Interfaces:**
- Produces: `ConsultationDag.assemble(...)` (same signature as `DoctorGraph.assemble`), replacing every `DoctorGraph` reference.

- [ ] **Step 1: Rename the class + file** — `DoctorGraph` → `ConsultationDag`, class name and filename. Update its own javadoc (it produces the consultation DAG).

- [ ] **Step 2: Update all call sites** — the 5 references above (`DefaultHealthSystem`, `HealthSystemTest`, `ExactRosterDoctor`, `ResourceCreationPipeline`, and the bnd prose). Grep `DoctorGraph` across the whole repo (`osgi/ exec/`, exclude `/target/`) — expected zero matches after.

- [ ] **Step 3: Verify the rename compiles + doctor in-container tests pass** — `flox activate -- ./mvnw -pl :doctor-core-test -am test -DskipTests=false -Dmaven.build.cache.skipCache=true`. Expected: BUILD SUCCESS, all doctor tests green. Confirm `grep -rn DoctorGraph osgi/ exec/ | grep -v /target/` is empty.

- [ ] **Step 4: Commit** — `git commit -m "refactor(doctor): rename DoctorGraph → ConsultationDag (the consultation DAG producer)"`.

---

## Task 4 (zone-1): systemd-adapter consumes the consult Document; probe emits a checkpoint Document

**Files:**
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/SystemdAdapterProbe.java` (return `Document` not `Observation`)
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/SimulatedSystemdAdapterProbe.java`
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/systemd/SeedSystemdAdapterEndpointGate.java` (the live probe path; stop building `Observation.failed(Symptom)`)
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterStage.java` (`consultDoctor` → call `consult(checkpoint)`, log `narration`; extend `checkpointDocument` with symptomKind/summary/details)
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/SystemdAdapterScenario.java` (jGiven Then asserts on Document fields, not `Observation`/`Symptom`)
- Modify: `exec/seed-master/src/test/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/SystemdAdapterStageFixture.java` + `SystemdAdapterVerdictTest.java` (the probe now returns a checkpoint Document)

**Interfaces:**
- Consumes: `ConsultingService.consult(Document)` (Task 1), the `SymptomKind`/`Coordinate` seam enums + the extended checkpoint field keys (Task 1).
- Produces: a systemd-adapter zone with zero `doctor.records` imports.

- [ ] **Step 1: Write/adapt the failing test** — `SystemdAdapterVerdictTest` already drives the failing-probe → verdict path. Extend it (or add a sibling) asserting the stage, on failure, calls `consult` and logs a narration line — using a stub `ConsultingService` returning a known `consultation` Document. The fixture's probe now returns a checkpoint `Document` (failed + `symptomKind = SymptomKind.CONNECTION_REFUSED.slug()`), not an `Observation`.

- [ ] **Step 2: Run it, verify it fails** — `flox activate -- ./mvnw -pl :seed-master -am package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=SystemdAdapterVerdictTest`. Expected: FAIL (probe/consult signatures changed).

- [ ] **Step 3: Migrate the probe + gate** — `SystemdAdapterProbe.probe(config)` returns `Document` (a checkpoint: status, failed, symptomKind slug, summary, details). `SeedSystemdAdapterEndpointGate` and `SimulatedSystemdAdapterProbe` build that Document from host-native values — the failure kind is written as `SymptomKind.CONNECTION_REFUSED.slug()` / `SymptomKind.TIMEOUT.slug()` (the seam enum, host-flat), never `Symptom.X` and never a literal. No `doctor.records` import remains in these files.

- [ ] **Step 4: Migrate the stage** — `checkpointDocument` adds `symptomKind`/`summary`/`details` (it already adds scenarioId/failed/override) and uses `Coordinate.READINESS_CHECKPOINT.slug()` for the coordinate. `consultDoctor(Document checkpoint)` calls `doctor.consult(checkpoint)`, logs `consultation.payload().path(FIELD_NARRATION).asText()`, and (for the runbook) stashes the `consultation` Document for the renderer (see Task 6 for how it reaches `RunbookRenderer`). Drop the `Observation`/`Symptom`/`RemediationPlan`/`ConsultationReport` imports. The `doctor` field type stays `ConsultingService` (now used via the Document verb).

- [ ] **Step 5: Migrate the scenario + fixture** — `SystemdAdapterScenario` Then-steps assert on the checkpoint/consultation Document, not `Observation`. Fixture + `SystemdAdapterVerdictTest` build a checkpoint Document.

- [ ] **Step 6: Run the test, verify it passes** — same as Step 2. Expected: PASS.

- [ ] **Step 7: Verify the worklist shrank** — full reactor `flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true`; the `realm-boundary` seed-master worklist must DROP (the systemd-adapter classes gone from it). Confirm `grep doctor.records SystemdAdapterStage.java SystemdAdapterProbe.java SimulatedSystemdAdapterProbe.java SeedSystemdAdapterEndpointGate.java SystemdAdapterScenario.java` is empty.

- [ ] **Step 8: Commit** — `git commit -m "feat(seed): systemd-adapter consult path crosses as a checkpoint+consultation Document"`.

---

## Task 5 (zone-2): cluster consumes the consult Document; remove the old seam verbs

**Files:**
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/ClusterReadinessProbe.java` (return `Document`)
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/LiveClusterReadinessProbe.java` + `SimulatedClusterReadinessProbe.java`
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/pipeline/stages/ClusterReadinessStage.java`
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/ClusterReadinessScenario.java`
- Modify: `osgi/doctor/doctor-port/src/main/java/io/nxmatic/rke2lab/doctor/port/ConsultingService.java` (REMOVE the three old verbs)
- Modify: `osgi/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/internal/Generalist.java` (remove the now-private-only old verbs from the public surface if they were `@Override`; keep internal helpers)
- Modify: tests calling `SimulatedClusterReadinessProbe.failingAt(..., Symptom)` (`NestedRunbookTest`)

**Interfaces:**
- Consumes: `consult(Document)` (Task 1).
- Produces: cluster zone doctor-free; `ConsultingService` no longer exposes `consult(Symptom,Observation)`/`consultedLine`/`cohortFinding`.

- [ ] **Step 1: Write/adapt the failing test** — the cluster jGiven scenario (`ClusterReadinessScenario`) asserts on the consultation Document. Mirror Task 4's test shape.

- [ ] **Step 2: Run it, verify it fails** — `flox activate -- ./mvnw -pl :seed-master -am package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ClusterReadiness*`. Expected: FAIL.

- [ ] **Step 3: Migrate the cluster probe + stage + scenario** — same shape as Task 4: `ClusterReadinessProbe.probe(config, phase)` returns a checkpoint `Document`; the live/simulated impls write the symptom slug via the seam enum (`SymptomKind.KUBECONFIG_MISSING.slug()` / `API_NOT_READY` / `CONTROLLER_NOT_READY`), never `Symptom.X`; the checkpoint coordinate is `Coordinate.READINESS_CHECKPOINT.slug()`; `ClusterReadinessStage.consultDoctor` calls `consult(checkpoint)`; drop `doctor.records` imports.

- [ ] **Step 4: Remove the three old seam verbs** — now that NO host code calls `consult(Symptom,Observation)`, `consultedLine`, `cohortFinding`, delete them from `ConsultingService` and from `Generalist`'s public surface (keep the internal `consult(Symptom,Observation)` as a PRIVATE helper of `Generalist.consult(Document)` — it is still the routing core). Verify the seam's `Import-Package` no longer needs `Symptom`/`Observation`/`RemediationPlan` for the consult verbs (it still imports them for the two reconstruction verbs — expected).

- [ ] **Step 5: Run the test, verify it passes** — same as Step 2. Expected: PASS.

- [ ] **Step 6: Verify** — full reactor green; the cluster classes gone from the `realm-boundary` worklist; `grep doctor.records ClusterReadinessStage.java ClusterReadinessProbe.java LiveClusterReadinessProbe.java SimulatedClusterReadinessProbe.java ClusterReadinessScenario.java` empty.

- [ ] **Step 7: Commit** — `git commit -m "feat(seed): cluster consult path crosses as a Document; drop the record-typed consult verbs"`.

---

## Task 6 (zone-1/2 tail): runbook reads `diagnosisAdoc`, loses its doctor imports

**Files:**
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/RunbookRenderer.java`
- Modify: whoever owns the consultation Documents the renderer needs (the `ConsultationLog` is replaced/augmented by a host-side list of consultation Documents — decide in Step 1 based on how `BootstrapStage` threads it; the spec says the host keeps a string-only carrier).
- Modify: `exec/seed-master/.../pipeline/BootstrapPipeline.java` / `PipelineState.java` if the consultation carrier threading changes.
- Test: `RunbookRenderingTest`, `NestedRunbookTest` (assert the diagnosis block reaches the `.adoc` via the Document string, not a `RemediationPlan`).

**Interfaces:**
- Consumes: the `consultation` Documents produced in Tasks 4-5 (carrying `diagnosisAdoc`).
- Produces: a `RunbookRenderer` with zero `doctor.records` imports.

- [ ] **Step 1: Write the failing test** — adapt `RunbookRenderingTest`: given a consultation Document with a known `diagnosisAdoc`, the rendered `.adoc` scenario carries that text in its `extendedDescription`. The renderer no longer takes a `ConsultationLog` of `ConsultationReport`; it takes the host-side carrier of consultation Documents (keyed by `checkpointId`).

- [ ] **Step 2: Run it, verify it fails** — `flox activate -- ./mvnw -pl :seed-master -am package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=RunbookRenderingTest,NestedRunbookTest`. Expected: FAIL.

- [ ] **Step 3: Migrate `RunbookRenderer`** — `injectDiagnosis` reads `consultation.payload().path(FIELD_DIAGNOSIS_ADOC).asText()` and calls `scenario.setExtendedDescription(...)`. DELETE `diagnosisBlock` (it lives in `Generalist` now, Task 2) and the `RemediationPlan`/`ReferralReply`/`ConsultationReport` imports. Keep the jGiven `ReportModel`/`ScenarioModel`/`AsciiDocReportGenerator` shell and the `checkpointId`→scenario join (jGiven identity, host-pure). The `Checkpoint.fromSlug` join: replace with the string `checkpointId` carried by the consultation Document (or keep `Checkpoint` IF it is host-resoluble — verify; if `Checkpoint` is `doctor.records`, replace it with the raw slug string + the jGiven scenario title lookup).

- [ ] **Step 4: Run the test, verify it passes** — same as Step 2. Expected: PASS.

- [ ] **Step 5: Verify** — `grep doctor.records RunbookRenderer.java` empty; full reactor green; the runbook classes gone from the worklist.

- [ ] **Step 6: Commit** — `git commit -m "feat(seed): runbook inserts the OSGi-rendered diagnosis AsciiDoc, drops doctor imports"`.

---

## Task 7 (close-out): verify the consult-path worklist is clear

**Files:** none (verification + the spec's "green" check).

- [ ] **Step 1: Full reactor** — `flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true`. Expected: BUILD SUCCESS, 0 test failures.

- [ ] **Step 2: Confirm the consult-path slice of the worklist is gone** — the `realm-boundary` seed-master WARN list no longer names `SystemdAdapterStage`, `ClusterReadinessStage`, the four probes, `SeedSystemdAdapterEndpointGate`, `RunbookRenderer`, or the two consult scenarios. The remaining entries are the reconstruction + resource families (2C/2D) — record the new count.

- [ ] **Step 3: Confirm no doctor type on the consult path** — `grep -rn 'doctor.records' exec/seed-master/src/main/java/.../pipeline/stages/SystemdAdapterStage.java .../ClusterReadinessStage.java .../bdd/*Probe*.java .../bdd/RunbookRenderer.java .../systemd/SeedSystemdAdapterEndpointGate.java` is empty.

- [ ] **Step 4: Update the memory** — mark 2B shipped in `world-exchange-2a-execution-state.md` (or a new 2B memory) + the MEMORY.md pointer; record the new worklist count and what 2C (reconstruction) / 2D (egress + schema + flip) inherit.

---

## Self-Review

- *Spec coverage:* zone-0 (Tasks 1-3: typed vocabulary + seam verb, Generalist impl, rename), zone-1 (Task 4), zone-2 (Task 5), runbook tail (Task 6), close-out (Task 7). Every spec unit has a task.
- *Order:* Task 1 (seam vocabulary + verb) precedes 4-5 (consumers) — the shared-seam-first constraint holds, and the `SymptomKind` enum the host needs is created before the host drops `Symptom`. Task 2 moves `diagnosisBlock` into `Generalist` BEFORE Task 6 deletes it from `RunbookRenderer` — no window where it is gone from both.
- *Type consistency:* the consultation Document field KEYS (`narration`, `diagnosisAdoc`, `symptomKind`, `summary`, `details`) are `ExchangeCatalog.FIELD_*` constants named once in Task 1. The closed VALUE domains are the seam enums `Coordinate`/`Action`/`SymptomKind` (Task 1), referenced via `.slug()` everywhere: `Coordinate.READINESS_CHECKPOINT.slug()` (Task 4/5 checkpoint), `Coordinate.CONSULTATION.slug()` (Task 2), `Action.STOP.slug()` (DefaultReadinessAuthority + SystemdAdapterStage), `SymptomKind.X.slug()` (Task 4/5 probes); `Generalist.toSymptom(SymptomKind)` maps to the OSGi-owned `Symptom` via an exhaustive switch (Task 2). No literal symptom/action/coordinate string survives.
- *Resolved before execution (controller):* `doctor-port` did NOT dep `exchange-port` — added in commit `864ec8d8`. `Checkpoint` IS a `doctor.records` enum → Task 6 uses the raw slug string, not the type. `Symptom` already has `parse(String)` but the host no longer touches `Symptom` at all — it uses the seam `SymptomKind`. No `ExchangeCatalogTest`; vocabulary assertions live in the new `ExchangeVocabularyTest` (enums) + `DocumentTest` (field keys).
- *Open verification for the executor:* Task 6 Step 3 — confirm the host-side consultation carrier threading through `BootstrapPipeline`/`PipelineState` (string-only) before deleting `RunbookRenderer.diagnosisBlock`.
