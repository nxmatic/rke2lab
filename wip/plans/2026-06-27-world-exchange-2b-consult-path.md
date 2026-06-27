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

## Task 1 (zone-0a): the `consult(Document)` seam verb + ExchangeCatalog constants

**Files:**
- Modify: `osgi/exchange/exchange-port/src/main/java/io/nxmatic/rke2lab/exchange/port/ExchangeCatalog.java`
- Modify: `osgi/doctor/doctor-port/src/main/java/io/nxmatic/rke2lab/doctor/port/ConsultingService.java`
- Test: `osgi/exchange/exchange-port/src/test/java/io/nxmatic/rke2lab/exchange/port/ExchangeCatalogTest.java` (extend the existing catalog test, or `DocumentTest`'s catalog assertions)

**Interfaces:**
- Consumes: `Document` (from 2A, `osgi/exchange/exchange-port`).
- Produces: `ConsultingService.consult(Document)→Document`; `ExchangeCatalog` constants `CONSULTATION` (coordinate `"consultation"`), `FIELD_NARRATION` (`"narration"`), `FIELD_DIAGNOSIS_ADOC` (`"diagnosisAdoc"`), `FIELD_SYMPTOM_KIND` (`"symptomKind"`), `FIELD_SUMMARY` (`"summary"`), `FIELD_DETAILS` (`"details"`). The checkpoint reuses 2A's `READINESS_CHECKPOINT` coordinate + `FIELD_SCENARIO_ID`/`FIELD_FAILED`/`FIELD_OVERRIDE`, plus the three new checkpoint fields above.

- [ ] **Step 1: Write the failing test** — assert the new `ExchangeCatalog` constants pin their canonical strings (mirror the existing `catalogConstantsAreTheCanonicalStrings` test).

```java
@Test
void consultationCoordinateAndFieldsArePinned() {
  assertEquals("consultation", ExchangeCatalog.CONSULTATION);
  assertEquals("narration", ExchangeCatalog.FIELD_NARRATION);
  assertEquals("diagnosisAdoc", ExchangeCatalog.FIELD_DIAGNOSIS_ADOC);
  assertEquals("symptomKind", ExchangeCatalog.FIELD_SYMPTOM_KIND);
  assertEquals("summary", ExchangeCatalog.FIELD_SUMMARY);
  assertEquals("details", ExchangeCatalog.FIELD_DETAILS);
}
```

- [ ] **Step 2: Run it, verify it fails** — `flox activate -- ./mvnw -pl :exchange-port -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=ExchangeCatalogTest`. Expected: FAIL (constants undefined).

- [ ] **Step 3: Add the constants** to `ExchangeCatalog` (private-ctor final class, mirror the existing constants' javadoc density).

- [ ] **Step 4: Add the seam verb** to `ConsultingService`:

```java
/**
 * Consult on a checkpoint: route its symptom + observation to the specialists and synthesize the
 * narration and the rendered AsciiDoc diagnosis, returned as a {@code consultation} Document. The
 * twin of {@link io.nxmatic.rke2lab.exchange.port.ReadinessAuthority#assess} — same checkpoint, the
 * consulting concern rather than the provisioning verdict.
 */
io.nxmatic.rke2lab.exchange.port.Document consult(io.nxmatic.rke2lab.exchange.port.Document checkpoint);
```

Leave the three old verbs (`consult(Symptom,Observation)`, `consultedLine`, `cohortFinding`) in place for now — they are removed in Task 5 once unused. Add the `exchange-port` dependency to `doctor-port`'s pom if not already present (check first; 2A may not have added it to doctor-port).

- [ ] **Step 5: Run the test, verify it passes** — same command as Step 2. Expected: PASS.

- [ ] **Step 6: Commit** — `git add` the three files; `git commit -m "feat(exchange): consult(Document) seam verb + consultation catalog constants"`.

---

## Task 2 (zone-0b): `Generalist` implements `consult(Document)`, rendering narration + diagnosisAdoc

**Files:**
- Modify: `osgi/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/internal/Generalist.java`
- Test: `osgi/doctor/doctor-core-test/src/main/java/io/nxmatic/rke2lab/doctor/` — a new `GeneralistConsultDocumentTest` (the doctor-core-test module runs in-container; follow the existing `GeneralistRecordRetrievalTest` shape).

**Interfaces:**
- Consumes: `ConsultingService.consult(Document)` (Task 1), the existing internal `consult(Symptom,Observation)→RemediationPlan` + `consultedLine`/`cohortFinding`, and the `diagnosisBlock` logic (moved in from `RunbookRenderer` — copy its body now, the host loses it in Task 7).
- Produces: a `consult(Document)` impl that returns a `consultation` Document with `narration` + `diagnosisAdoc` + echoed `checkpointId`.

- [ ] **Step 1: Write the failing test** — build a checkpoint Document (scenarioId + failed + symptomKind="connection-refused" + summary + details), call the assembled `Generalist`'s `consult(Document)`, assert the returned Document's `coordinate` is `consultation`, its `narration` is non-empty, and its `diagnosisAdoc` contains `"⚕ Diagnosis:"`.

- [ ] **Step 2: Run it, verify it fails** — `flox activate -- ./mvnw -pl :doctor-core-test -am test -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=GeneralistConsultDocumentTest`. Expected: FAIL (method not implemented / returns nothing).

- [ ] **Step 3: Implement `consult(Document)` in `Generalist`:**

```java
@Override
public Document consult(Document checkpoint) {
  final var payload = checkpoint.payload();
  final Symptom symptom = Symptom.fromSlug(payload.path(ExchangeCatalog.FIELD_SYMPTOM_KIND).asText());
  final Observation observation = observationFrom(payload); // status/summary/details → Observation, OSGi-side
  final RemediationPlan plan = consult(symptom, observation);  // the existing record-typed internal path
  final ObjectNode out = mapper.createObjectNode();
  out.put(ExchangeCatalog.FIELD_SCENARIO_ID, payload.path(ExchangeCatalog.FIELD_SCENARIO_ID).asText());
  out.put(ExchangeCatalog.FIELD_NARRATION, narrationLine(symptom)); // was consultedLine + cohortFinding, joined
  out.put(ExchangeCatalog.FIELD_DIAGNOSIS_ADOC, diagnosisBlock(plan)); // moved in from RunbookRenderer
  return new Document(ExchangeCatalog.DOMAIN_DOCTOR, ExchangeCatalog.CONSULTATION, out);
}
```

Add the private helpers: `observationFrom(JsonNode)` (rebuild the `Observation` OSGi-side from the checkpoint's summary/details), `narrationLine(Symptom)` (join `consultedLine` + `cohortFinding`), and `diagnosisBlock(RemediationPlan)` (copy verbatim from `RunbookRenderer.diagnosisBlock` — the `⚕/🔬/℞` AsciiDoc StringBuilder). Add `Symptom.fromSlug(String)` to `doctor-records` if it does not exist (mirror the `Checkpoint.fromSlug` pattern). `Generalist` needs an `ObjectMapper`; reuse or add one. doctor-core's pom needs the `exchange-port` dependency (check; 2A added it for `DefaultReadinessAuthority`, so likely present).

- [ ] **Step 4: Run the test, verify it passes** — same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(doctor): Generalist.consult(Document) renders narration + diagnosis AsciiDoc OSGi-side"`.

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
- Consumes: `ConsultingService.consult(Document)` (Task 1), the extended checkpoint fields (Task 1 constants).
- Produces: a systemd-adapter zone with zero `doctor.records` imports.

- [ ] **Step 1: Write/adapt the failing test** — `SystemdAdapterVerdictTest` already drives the failing-probe → verdict path. Extend it (or add a sibling) asserting the stage, on failure, calls `consult` and logs a narration line — using a stub `ConsultingService` returning a known `consultation` Document. The fixture's probe now returns a checkpoint `Document` (failed + symptomKind), not an `Observation`.

- [ ] **Step 2: Run it, verify it fails** — `flox activate -- ./mvnw -pl :seed-master -am package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true -Dtest=SystemdAdapterVerdictTest`. Expected: FAIL (probe/consult signatures changed).

- [ ] **Step 3: Migrate the probe + gate** — `SystemdAdapterProbe.probe(config)` returns `Document` (a checkpoint: status, failed, symptomKind slug, summary, details). `SeedSystemdAdapterEndpointGate` and `SimulatedSystemdAdapterProbe` build that Document from host-native values — the failure kind becomes the symptom *slug* string (`"connection-refused"`, `"timeout"`), never `Symptom.X`. No `doctor.records` import remains in these files.

- [ ] **Step 4: Migrate the stage** — `checkpointDocument` adds `symptomKind`/`summary`/`details` (it already adds scenarioId/failed/override). `consultDoctor(Document checkpoint)` calls `doctor.consult(checkpoint)`, logs `consultation.payload().path(FIELD_NARRATION).asText()`, and (for the runbook) stashes the `consultation` Document for the renderer (see Task 6 for how it reaches `RunbookRenderer`). Drop the `Observation`/`Symptom`/`RemediationPlan`/`ConsultationReport` imports. The `doctor` field type stays `ConsultingService` (now used via the Document verb).

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

- [ ] **Step 3: Migrate the cluster probe + stage + scenario** — same shape as Task 4: `ClusterReadinessProbe.probe(config, phase)` returns a checkpoint `Document`; the live/simulated impls write the symptom slug; `ClusterReadinessStage.consultDoctor` calls `consult(checkpoint)`; drop `doctor.records` imports.

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

- *Spec coverage:* zone-0 (Tasks 1-3: seam verb + Generalist impl + rename), zone-1 (Task 4), zone-2 (Task 5), runbook tail (Task 6), close-out (Task 7). Every spec unit has a task.
- *Order:* Task 1 (seam) precedes 4-5 (consumers) — the shared-seam-first constraint holds. Task 2 moves `diagnosisBlock` into `Generalist` BEFORE Task 6 deletes it from `RunbookRenderer` — no window where it is gone from both.
- *Type consistency:* the consultation Document fields (`narration`, `diagnosisAdoc`, `symptomKind`, `summary`, `details`) are named once in Task 1 and reused verbatim in Tasks 2/4/5/6.
- *Open verification for the executor:* Task 6 Step 3 flags `Checkpoint` — confirm whether it is `doctor.records` (then replace with the string slug) or host-resoluble. Task 1 flags the `exchange-port` dependency on `doctor-port` (add if absent).
