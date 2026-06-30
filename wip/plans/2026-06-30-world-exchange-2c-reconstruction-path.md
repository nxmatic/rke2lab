# World-exchange 2C — the reconstruction path crosses as opaque Documents — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drive the `REALM_BOUNDARY` gate worklist from 38 flat classes referencing `doctor.records` to **zero**, then flip the gate `WARN`→`ERROR` — the build-enforced lock that proves the host and OSGi realms are separated. This is the merge point.

**Architecture:** The PEER MODEL (spec `world-exchange-2c-reconstruction-path-spec.adoc`). Host and OSGi are peers: the host knows the STACK (opaque to OSGi), OSGi knows the DOCTOR (opaque to the host), joined only by opaque `Document` blobs + the shared `Checkpoint`/`Patient` identities. Three host-provided ports flip record→Document: two read journals (`MedicalRecordJournal.historyOf(Patient)`, `InterventionJournal.entries()`) yield `List<Document>`; the write port `InterventionLedgerWriter.append(Document)` takes a Document; the review verb collapses to `ConsultingService.reviewDrift():void` (no-arg — the spec's `Checkpoint` parameter is vestigial, see Global Constraints). The flat readers move OSGi-side (Layer-2, behind the ports); `SnapshotView`/`SnapshotEntry`/`StackCoordinate` become host-internal (Layer-1, pulumi-edge).

**Tech Stack:** Maven multi-module (reactor-only, `-am`), bnd-maven-plugin (OSGi bundles: `type=seam` system-exported flat, `type=record` not-exported, `type=model` installed), Declarative Services (`@Component`/`@Reference`), embedded Felix, jackson (each realm uses its own — no jackson type crosses), jGiven (in-container BDD), `maven-embed-staging-ext` (the `REALM_BOUNDARY` gate), flox JDK 25.

## Global Constraints

- **Spec of record:** `docs/architecture/osgi/world-exchange-2c-reconstruction-path-spec.adoc` (the PEER MODEL, the two verified cases, the 5-zone cut). Read it before Task 1.
- **One design correction to the spec, already adjudicated with the user (2026-06-30):** the spec's 3-port table says `DocumentJournal.historyOf(Checkpoint)`, but two distinct host reads cross — the per-`Patient` medical-record timeline AND the fixed `intervention-ledger/dev` stack. Resolution: **two focused read ports**, the record read keyed by **`Patient`** (matches `MedicalRecordRegistry.recordFor(Patient)` and `MedicalRecordReader.read(Patient)`), the ledger read **unkeyed** (`InterventionJournal.entries()`). `Checkpoint` stays the key of `reviewDrift`/consult only. The table's `Checkpoint` for the record read is a slip.
- **One type the spec never names — `StackCoordinate`:** in `doctor-records`, referenced ONLY by host pulumi-edge (`InterventionLedgerLayout`, `InterventionLedgerSource`, `PulumiInterventionLedgerWriter`). It is stack vocabulary, the *inverse* of `Checkpoint`/`Patient`: it goes **host-internal to pulumi-edge** alongside `SnapshotView`/`SnapshotEntry`, NOT to the seam. (Only identities BOTH worlds name go to the seam.)
- **Zone-4 "CLI tools" cannot be deferred to a late zone — they migrate INSIDE zone-3.** `MedicalRecordDump` consumes `MedicalRecordReader` (which zone-3 moves OSGi-side); `RecordInterventionCommand` consumes `append(Intervention)` (which zone-3 flips to `append(Document)`). Deferring them would break the build. Each rides the surface it is coupled to in zone-3. Only `ClusterSchemaRef` (the genuinely-independent cross-domain isolate) remains as its own zone-4.
- **No dedicated worktree** — implement directly in `feature/cluster-edge` (user decision; 2B integrated, no concurrent mutator). Base is `feature/cluster-edge` (`origin/main` has none of this).
- **Reactor-only resolution** — NEVER `mvn install` project artifacts to `~/.m2`. Siblings resolve from the reactor via `-am`. `maven-embed-staging-ext` is the documented exception (RELEASE coord via `.mvn/extensions.xml`); this plan does not touch it.
- **Verify recipe (every zone's test):** `flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true`. A pass is `BUILD SUCCESS` + the staging line shows `realm-boundary: 0 error, N warn` with **N strictly lower than the prior zone** + the doctor in-container tests in a `Tests run:` line (`DoctorCoreInContainerTest`, `DoctorPortInContainerTest`, `ManifestsCoreInContainerTest`). A two-realm collision (a `LinkageError`/unsatisfied resolve) surfaces ONLY in-container — a flat `-Dtest=` run hides it, so NEVER substitute a flat run for the full reactor on seam/Document changes.
- **The worklist is the progress meter.** After each zone, read the gate's per-class WARN list and confirm the classes that zone targeted are gone. Capture it with: `… 2>&1 | grep -iE "realm.bound|flat .* references" | sort -u`. zone-5 fails the build if ANY flat class still references `doctor.records` — that failure IS the lock working.
- **`doctor.records` is `type=record` (NOT system-exported)** — that is why host references to `Patient`/`Checkpoint` are WARN leaks today. Moving them to `world-gateway` (`type=seam`) makes both realms' references legal at runtime.
- **sed caveat:** GNU sed under flox rejects BSD `-i ''`; use `perl -0pi -e '...'` for in-place edits.
- **Every module keeps a `<description>`.** New pom dependencies use reactor coordinates (groupId `io.nxmatic.rke2lab`, `${revision}` version) — match a sibling `<dependency>` block verbatim.
- **Commit trailer:** end every commit message with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## Current state (verified 2026-06-30)

**Gate:** `realm-boundary: 0 error, 38 warn` — all 38 are flat classes in `seed-master`/`pulumi-edge` referencing `io.nxmatic.rke2lab.doctor.records.*`. Three packages carry `@GovernedBy(REALM_BOUNDARY, WARN)`:
- `osgi/domains/cluster/cluster-port/src/main/java/io/nxmatic/rke2lab/cluster/port/package-info.java:8`
- `osgi/domains/doctor/doctor-port/src/main/java/io/nxmatic/rke2lab/doctor/port/package-info.java:4`
- `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/package-info.java:15`

**Worlds (both flat):** the host spans `exec/seed-master/` (control plane — stages, scenarios, CLIs, `DriftReview`, `RunbookRenderer`, `ResourceCreationPipeline`, `BootstrapPipeline`) AND `host/pulumi/pulumi-edge/` (Pulumi adapter — `StackHandleSnapshotSource`, `LiveMedicalRecordRegistry`, `PulumiInterventionLedgerWriter`, `InterventionLedgerSource`, `InterventionLedgerSource`, `InterventionLedgerLayout`).

**Seam today:** `osgi/foundation/world-gateway` (artifactId `world-gateway`, package `io.nxmatic.rke2lab.world.gateway.port`, `type=seam`). Holds `Document`(domain,coordinate,payload:String), `Domain`(DOCTOR), `Coordinate`(READINESS_CHECKPOINT/READINESS_VERDICT/CONSULTATION), `Action`, `SymptomKind`, `ReadinessAuthority`, `WorldGatewayCatalog`(FIELD_* keys).

**Doctor modules:** `doctor-records` (type=record, holds Checkpoint/Patient/StackCoordinate/SnapshotView/SnapshotEntry/MedicalRecord/Visit/Intervention/InterventionLedger/Observation/Symptom/SchemaRef/…), `doctor-port` (type=seam, holds the readers + MedicalRecordRegistry/SnapshotSource/ConsultingService/InterventionLedgerWriter/ConsultationLog/HealthSystem), `doctor-spi`, `doctor-core` (Generalist/DefaultHealthSystem/ConsultationDag/ClinicalAccess, sealed `.internal`), `doctor-core-test` + `doctor-port-test` (in-container fragments).

**The leak shape:** `BootstrapPipeline.java:170-184` — the host builds `LiveMedicalRecordRegistry` (`recordFor(Patient)→MedicalRecord`) + `PulumiInterventionLedgerWriter` (`append(Intervention)`) and `registerService`s both; `DefaultHealthSystem` `@Reference`s them. `MedicalRecord`/`Intervention` cross host↔OSGi = the violation.

**`DocumentJournal`/`historyOf`/`reviewDrift` do not exist yet** — all NEW in 2C (verified: zero occurrences).

---

### Task 1 (zone-1): Move `Checkpoint` + `Patient` identities to the `world-gateway` seam

**Files:**
- Move (git mv): `osgi/domains/doctor/doctor-records/src/main/java/io/nxmatic/rke2lab/doctor/records/Checkpoint.java` and `Patient.java` → `osgi/foundation/world-gateway/src/main/java/io/nxmatic/rke2lab/world/gateway/port/`
- Modify (package line + new imports): the moved files; `world-gateway/bnd.bnd` (none — same package already exported); `osgi/domains/doctor/doctor-records/pom.xml` (add `world-gateway` dependency); 4 in-package doctor-records consumers (`ConsultationReport.java`, `ProblemRef.java`, `Referral.java`, `MedicalRecord.java` — add imports); every other importer of `io.nxmatic.rke2lab.doctor.records.Checkpoint`/`.Patient`
- Test: no new test; the gate worklist shrink + green reactor IS the test.

**Interfaces:**

- Consumes: nothing (first zone).
- Produces: `io.nxmatic.rke2lab.world.gateway.port.Checkpoint` (enum, same body) and `io.nxmatic.rke2lab.world.gateway.port.Patient` (record, same body) — both now system-exported seam types both realms may name. `doctor-records` now `→ world-gateway` (a record bundle importing a seam; acyclic — world-gateway deps nothing).

- [ ] **Step 1: git mv the two files into the seam package dir**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
WG=osgi/foundation/world-gateway/src/main/java/io/nxmatic/rke2lab/world/gateway/port
DR=osgi/domains/doctor/doctor-records/src/main/java/io/nxmatic/rke2lab/doctor/records
git mv "$DR/Checkpoint.java" "$WG/Checkpoint.java"
git mv "$DR/Patient.java"    "$WG/Patient.java"
```

- [ ] **Step 2: Rewrite the package declaration in both moved files**

In `…/world/gateway/port/Checkpoint.java` and `Patient.java`, change the first line `package io.nxmatic.rke2lab.doctor.records;` → `package io.nxmatic.rke2lab.world.gateway.port;`. Leave the bodies (javadoc, enum constants, record components) byte-for-byte unchanged. `Checkpoint`'s javadoc references `ConsultationReport#checkpointId()` — that `{@link}` now needs a fully-qualified `io.nxmatic.rke2lab.doctor.records.ConsultationReport` or it will be an unresolved javadoc reference; replace the two `{@link ConsultationReport#...}` occurrences with the FQN form (javadoc only; no compile dependency on doctor-records — keeps the move acyclic).

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
WG=osgi/foundation/world-gateway/src/main/java/io/nxmatic/rke2lab/world/gateway/port
for f in "$WG/Checkpoint.java" "$WG/Patient.java"; do
  perl -0pi -e 's{^package io\.nxmatic\.rke2lab\.doctor\.records;}{package io.nxmatic.rke2lab.world.gateway.port;}m' "$f"
done
perl -0pi -e 's{\{\@link ConsultationReport#checkpointId\(\)\}}{\{\@link io.nxmatic.rke2lab.doctor.records.ConsultationReport#checkpointId()\}}g' "$WG/Checkpoint.java"
```

- [ ] **Step 3: Add the `world-gateway` dependency to `doctor-records/pom.xml`**

`doctor-records` now references the two seam types from inside `ConsultationReport`/`ProblemRef`/`Referral`/`MedicalRecord`. Add (copy the exact `<dependency>` shape from a sibling, e.g. `doctor-core/pom.xml`'s `world-gateway` entry):

```xml
    <dependency>
      <groupId>io.nxmatic.rke2lab</groupId>
      <artifactId>world-gateway</artifactId>
      <version>${revision}</version>
    </dependency>
```

Place it next to the existing `domain-annotations` dependency.

- [ ] **Step 4: Add the import to the 4 in-package doctor-records consumers**

`ConsultationReport.java` and `ProblemRef.java` use `Checkpoint`; `Referral.java` and `MedicalRecord.java` use `Patient` — all by simple name today (same package). Add the matching import to each (after the `package` line / among existing imports):
- `ConsultationReport.java`, `ProblemRef.java`: `import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;`
- `Referral.java`, `MedicalRecord.java`: `import io.nxmatic.rke2lab.world.gateway.port.Patient;`

(Verify each truly uses the type — `grep -n "Checkpoint\|Patient" <file>` — some may reference it only in javadoc, in which case use the FQN in the `{@link}` instead of an import.)

- [ ] **Step 5: Rewrite every other importer across the repo**

Repo-wide, rewrite the import FQN for both types (declarations were handled in Step 2; this catches all consumers — host main+test, OSGi main+test):

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -rln "io\.nxmatic\.rke2lab\.doctor\.records\.Checkpoint" --include="*.java" . | grep -v /target/ | while read -r f; do
  perl -0pi -e 's{io\.nxmatic\.rke2lab\.doctor\.records\.Checkpoint}{io.nxmatic.rke2lab.world.gateway.port.Checkpoint}g' "$f"
done
grep -rln "io\.nxmatic\.rke2lab\.doctor\.records\.Patient" --include="*.java" . | grep -v /target/ | while read -r f; do
  perl -0pi -e 's{io\.nxmatic\.rke2lab\.doctor\.records\.Patient}{io.nxmatic.rke2lab.world.gateway.port.Patient}g' "$f"
done
```

⚠️ Some doctor-core / doctor-core-test files import `io.nxmatic.rke2lab.doctor.records.*` (wildcard) and reference `Checkpoint`/`Patient` by simple name — the wildcard no longer supplies them. After the sweep, add an explicit `import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;`/`Patient;` to any file that uses the simple name but had only the wildcard. Find them:

```bash
# files using the simple name with NO explicit world.gateway import yet:
for t in Checkpoint Patient; do
  grep -rln "\b$t\b" --include="*.java" osgi/ exec/ host/ | grep -v /target/ | while read -r f; do
    grep -q "io.nxmatic.rke2lab.world.gateway.port.$t" "$f" || echo "ADD import $t -> $f"
  done
done
```

For each `ADD import` line, confirm the file genuinely uses the type (not just a substring) and add the explicit import. (doctor-core's `Generalist`, `ConsultationDag`, `ClinicalAccess`, `DefaultHealthSystem`, `Grant`, `GrantPolicy` use `Patient`; the test fixtures use both.)

- [ ] **Step 6: Confirm no `doctor.records.Checkpoint`/`.Patient` reference remains**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -rn "doctor\.records\.Checkpoint\|doctor\.records\.Patient" --include="*.java" . | grep -v /target/ | grep -v "docs/"
```

Expected: empty.

- [ ] **Step 7: Full reactor build — expect green + worklist shrink**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | tee /tmp/2c-zone1.log | grep -iE "BUILD (SUCCESS|FAILURE)|realm.bound|Tests run:|cannot find symbol|package .* does not exist" | tail -40
```

Expected: `BUILD SUCCESS`; `realm-boundary: 0 error, N warn` with **N < 38** (the identity-only flat classes — `BootstrapPipeline$ComponentBoundPipeline`, `ResourceCreationPipeline`, `RunbookRenderer`, and the `$…` checkpoint-only inner classes — drop out; classes that ALSO reference Observation/records stay). Doctor in-container tests green (they prove the seam re-export resolved in-container, since `Checkpoint`/`Patient` now cross the realm boundary as seam types). Record the new N in the ledger.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(world-exchange): 2C zone-1 — Checkpoint + Patient become world-gateway seam identities

Both identities are named by BOTH realms (host flat + doctor bundle), so they
belong on the system-exported seam, not in the type=record doctor bundle (which
is why the host's references were realm leaks). doctor-records now imports them
from world-gateway (a record bundle → seam, acyclic). Worklist shrinks by the
identity-only flat classes.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2 (zone-2): The consult residue — `Observation`/`Symptom` leave the host

This finishes the probe→Document migration that 2B's Task 4+5+6 deliberately skipped (the I-1 finding). Today the host probes return the doctor type `Observation` and the jGiven scenarios assert on `observation.status()`/`.summary()`. After this zone the probes return a checkpoint `Document` (the `READINESS_CHECKPOINT` coordinate, one observation in the `FIELD_OBSERVATIONS` shape), the scenarios assert on parsed Document fields, the gates build the Document directly with `SymptomKind` (the flat seam enum, already present), and no host class names `Observation` or `Symptom`. OSGi keeps reconstructing `Observation` internally (`Generalist.observationsFrom` already does) — those two types stay doctor-records, OSGi-only.

**Files:**
- Modify (probe contracts return `Document`): `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/SystemdAdapterProbe.java`, `ClusterReadinessProbe.java`
- Modify (build the Document instead of Observation): `…/controlplane/systemd/SeedSystemdAdapterEndpointGate.java`, `…/bdd/LiveClusterReadinessProbe.java`, `…/bdd/SimulatedSystemdAdapterProbe.java`, `…/bdd/SimulatedClusterReadinessProbe.java`
- Modify (assert on Document fields): `…/bdd/SystemdAdapterScenario.java`, `…/bdd/ClusterReadinessScenario.java`
- Modify (consume the Document directly; drop the `Observation`/`Symptom` imports + `consultCheckpoint(Observation)`): `…/controlplane/pipeline/stages/SystemdAdapterStage.java`, `ClusterReadinessStage.java`
- Modify (tests that name `Observation`/`Symptom`): the `seed-master` test fixtures/tests in the worklist (`SystemdAdapterStageFixture`, `SeedSystemdAdapterEndpointGateTest`, `FakeSystemdAdapterProbes`, `FakeClusterReadinessProbes`, etc.) — flip their assertions/builders to the Document shape
- No new production type unless Step 1 shows one is needed (the seam already has `Document`, `WorldGatewayCatalog.FIELD_*`, `SymptomKind`, `Domain`, `Coordinate`).

**Interfaces:**

- Consumes: zone-1's seam `Checkpoint` (the probes/stages already import it from `world.gateway.port` after Task 1).
- Produces: `SystemdAdapterProbe.probe(BootstrapConfig) → Document` and `ClusterReadinessProbe.probe(BootstrapConfig, ClusterReadinessPhase) → Document`. The host consult path holds zero `Observation`/`Symptom`. The consult Document shape on the wire is UNCHANGED (still `FIELD_SCENARIO_ID` + `FIELD_RECORDED_AT` + `FIELD_OBSERVATIONS` list of the `toOutputMap` shape), so `Generalist.consult` needs no change.

- [ ] **Step 1: Read the current shape and decide the flat observation builder**

Read `SystemdAdapterStage.consultCheckpoint(Observation)` (the `FIELD_OBSERVATIONS` list is built from `observation.toOutputMap()`) and `Observation.toOutputMap()` (status/summary/`Symptom.ENVELOPE_KEY`→slug/details). The host must build that SAME flat map without the `Observation` type. Add a small private helper on the host that builds the flat observation map from `(status, SymptomKind symptom, summary, details)` — the inverse of `Generalist.observationsFrom`. Put it where both probes and the gate can reach it: a package-private static `ObservationView` record (flat, host-only) in `io.nxmatic.rke2lab.controlplane.bdd`, with `status`, `Optional<SymptomKind>`, `summary`, `Map<String,Object> details`, an `ok(...)`/`failed(SymptomKind,...)` factory pair mirroring `Observation`, and a `toOutputMap()` producing the identical flat map (symptom slug under the literal key `"symptom"` — confirm `Symptom.ENVELOPE_KEY == "symptom"`). This is host-internal scaffolding, NOT a seam type — it never crosses; only its `toOutputMap()` does, inside the Document payload.

(If Step 1's reading shows the scenarios/stages can assert directly on the Document payload without a flat record, prefer that — fewer types. Decide from the actual code; the `ObservationView` record is the fallback if the scenario assertions need a typed handle.)

- [ ] **Step 2: Flip the two probe contracts to return `Document`**

`SystemdAdapterProbe`: `Document probe(BootstrapConfig config);` — drop `import …doctor.records.Observation;`. Update the javadoc (it currently describes returning an `Observation`). `ClusterReadinessProbe`: `Document probe(BootstrapConfig config, ClusterReadinessPhase phase);` — same.

- [ ] **Step 3: Build the Document in the gate + the live/simulated probes**

In `SeedSystemdAdapterEndpointGate`, the methods returning `Observation.ok/failed/of` now build an `ObservationView` (or the map directly) and wrap it into a `READINESS_CHECKPOINT` Document (`Domain.DOCTOR.slug()`, `Coordinate.READINESS_CHECKPOINT.slug()`, payload = a JSON object with `FIELD_SCENARIO_ID`=the checkpoint slug, `FIELD_RECORDED_AT`=the run instant, `FIELD_OBSERVATIONS`=`[view.toOutputMap()]`). The symptom enum maps `Symptom.CONNECTION_REFUSED`→`SymptomKind.CONNECTION_REFUSED` etc. — `SymptomKind` already has the same five constants and `.slug()`; use it directly, drop `import …doctor.records.Symptom;`. Do the same in `LiveClusterReadinessProbe.toObservation(...)` (its `KUBECONFIG_PUBLISHED→KUBECONFIG_MISSING` etc. mapping moves to `SymptomKind`) and in `SimulatedSystemdAdapterProbe`/`SimulatedClusterReadinessProbe`.

⚠️ The host now serializes the payload with ITS jackson (flat) — `SystemdAdapterStage` already holds an `ObjectMapper mapper` and a `serialize(...)`; reuse that pattern. No `JsonNode`/jackson type crosses the seam (Document.payload is a String).

- [ ] **Step 4: Assert on Document fields in the scenarios**

In `SystemdAdapterScenario.When`, the `@ProvidedScenarioState` becomes `Document observation;` (or keep the field name; rename type). `the_systemd_adapter_probe_runs()` stores `probe.probe(config)`. In `Then`, `the_probe_reports_status(expected)` parses the Document payload (host jackson), reads the first `FIELD_OBSERVATIONS` entry's `"status"`, and asserts; `the_summary_mentions(fragment)` reads its `"summary"`. `capturedObservation()` returns the `Document` (rename to `capturedCheckpoint()` for honesty; update the one caller in `SystemdAdapterStage`). Same for `ClusterReadinessScenario` (its `@ProvidedScenarioState Map<ClusterReadinessPhase, Observation>` becomes `Map<ClusterReadinessPhase, Document>`; the `observation.isOk()` check becomes a parsed-status check — add a host helper `isOk(Document)` reading the first observation's status).

- [ ] **Step 5: Simplify the two stages to consume the Document**

`SystemdAdapterStage`: `capturedObservation()`→`capturedCheckpoint()` now yields the Document directly; `consultDoctor(Observation)` becomes `consultDoctor(Document checkpoint)` that just guards (has a symptom-bearing observation?) and calls `doctor.consult(checkpoint)` — the `consultCheckpoint(Observation)` builder is GONE (the probe/gate built the Document). Drop `import …doctor.records.Observation;` and `.Symptom;`. The symptom-presence guard reads the parsed payload (`FIELD_OBSERVATIONS`[*] has a non-empty `"symptom"`). `ClusterReadinessStage`: the `Map<Phase,Observation>` becomes `Map<Phase,Document>`; `consultDoctor(phaseObservations)` merges the phase observations into one checkpoint Document carrying all observations in `FIELD_OBSERVATIONS` (it already builds a consult checkpoint — adapt it to read the Documents' observation entries instead of `Observation.toOutputMap()`).

- [ ] **Step 6: Fix the seed-master tests that name `Observation`/`Symptom`**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -rln "doctor\.records\.\(Observation\|Symptom\)" --include="*.java" exec/seed-master/src/test | grep -v /target/
```

For each: the fakes (`FakeSystemdAdapterProbes`, `FakeClusterReadinessProbes`, `SimulatedSystemdAdapterProbe` if test-side) now return `Document`s; the assertion tests (`SeedSystemdAdapterEndpointGateTest`, `SystemdAdapterStageFixture`) parse Document fields. Use the same host helpers from Steps 1/4.

- [ ] **Step 7: Confirm `Observation`/`Symptom` are gone from the host**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -rn "doctor\.records\.Observation\|doctor\.records\.Symptom\|\bObservation\b\|\bSymptom\b" --include="*.java" exec/seed-master host/pulumi | grep -v /target/ | grep -v "SymptomKind"
```

Expected: empty (no host reference to the doctor `Observation`/`Symptom`; `SymptomKind` references are fine and expected). `pulumi-edge` had only test references (`LiveMedicalRecordRegistryTest`, `PulumiInterventionLedgerWriterLiveTest`) — flip those too.

- [ ] **Step 8: Full reactor build — expect green + worklist shrink**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | tee /tmp/2c-zone2.log | grep -iE "BUILD (SUCCESS|FAILURE)|realm.bound|Tests run:|cannot find symbol" | tail -40
```

Expected: `BUILD SUCCESS`; `realm-boundary` N drops by the consult-residue classes (the probes, gates, scenarios, the two stages' Observation/Symptom refs). Doctor in-container tests green. Record the new N.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(world-exchange): 2C zone-2 — the consult residue, Observation/Symptom leave the host

The probes return a checkpoint Document; the jGiven scenarios assert on parsed
Document fields; the gates build the flat observation shape with SymptomKind (the
seam enum). OSGi keeps reconstructing Observation internally — the doctor types no
longer cross. Finishes the probe→Document migration 2B's Task 4+5+6 skipped.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3a (zone-3, write path): `InterventionLedgerWriter.append(Document)` + the CLI canonicalizes through OSGi

The write port flips record→Document, and the drift path that drives it (`Generalist`/`DriftSpecialist`) feeds it a Document. `RecordInterventionCommand` (CLI ingress) follows **Option A** (user decision 2026-06-30): the host parses argv to raw strings, boots the embedded framework, and calls an OSGi `canonicalize(Document) → Document` verb that validates the facts (the doctor owns the intervention schema), then `append`s the canonical Document. No doctor type host-side.

**Files:**
- Modify (seam contract): `osgi/domains/doctor/doctor-port/src/main/java/io/nxmatic/rke2lab/doctor/port/InterventionLedgerWriter.java` — `append(Intervention)` → `append(Document)`
- Modify (host impl): `host/pulumi/pulumi-edge/src/main/java/io/nxmatic/rke2lab/pulumi/edge/PulumiInterventionLedgerWriter.java` — deserialize the Document payload to a `Map<String,Object>`, feed `InterventionResource` directly (drop the `Intervention` import)
- Modify (OSGi producer of the drift Document): `osgi/domains/doctor/doctor-core/src/main/java/io/nxmatic/rke2lab/doctor/internal/DriftSpecialist.java` — its `InterventionLedgerWriter writer` field now takes a Document; wrap `intervention.toOutputMap()` into an `interventionDocument(Intervention)` before `writer.append(...)` (the `Intervention`/`toOutputMap` stay OSGi-internal)
- Add (seam verb for ingress canonicalization): a method on `ConsultingService` (or a new small `@Component` service `InterventionIntake`) — `Document canonicalize(Document rawFacts)`; add a `Coordinate.INTERVENTION_REQUEST` + `Coordinate.INTERVENTION` (or reuse one) and the `WorldGatewayCatalog.FIELD_*` keys the raw facts carry (`FIELD_PROBLEM`, `FIELD_WHAT`, `FIELD_PROVENANCE`, `FIELD_PRESCRIPTION_REF`, `FIELD_WHEN`)
- Add (OSGi impl of canonicalize): in `doctor-core` — parse the raw-facts Document with the doctor schema (`ProblemRef.parse`/`Provenance.parse`/`RemediationProgramRef.parse`), build the `Intervention`, return its `toOutputMap()` as a canonical Document (or an error verdict Document on a bad ref)
- Modify (CLI): `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/bdd/RecordInterventionCommand.java` — parse argv to raw strings (no doctor type), boot `BootPipeline.embedded()`, `awaitService` the canonicalize verb, build the raw-facts Document, call canonicalize, then `append(canonicalDocument)`; drop `Intervention`/`ProblemRef`/`Provenance`/`RemediationProgramRef` imports
- Modify (tests): `RecordInterventionCommandTest`, `InterventionLedgerRoundTripLiveTest`, `PulumiInterventionLedgerWriterLiveTest`, and any `seed-master`/`pulumi-edge` test asserting `append(Intervention)` or building one

**Interfaces:**

- Consumes: zone-1 `Checkpoint`/`Patient` seam; zone-2's host jackson + Document-build pattern; the launcher `BootPipeline.embedded().during(topic, Class<T>, Consumer<T>)` + `BootedFramework.awaitService` (precedent: `BootstrapStage.runBootstrapPipeline`, `BootstrapPipeline.admitPatient`).
- Produces: `InterventionLedgerWriter.append(Document)`; `canonicalize(Document)→Document` on the doctor seam; `WorldGatewayCatalog.FIELD_PROBLEM/FIELD_WHAT/FIELD_PROVENANCE/FIELD_PRESCRIPTION_REF/FIELD_WHEN`; a new `Coordinate` constant for the intervention request/canonical document. zone-3b consumes `append(Document)`.

- [ ] **Step 1: Decide the canonicalize seam surface (read first)**

Read `ConsultingService` (it already has `adapt(Class)` + `consult(Document)`), `DefaultHealthSystem` (how the doctor is published), and `BootPipeline.embedded().during(topic, Class<T> service, Consumer<T> tail)` (line ~113). Two faithful shapes — pick the one matching the repo's `@Component` discipline:

**(a)** add `Document canonicalize(Document rawFacts)` to `ConsultingService`, implemented by `Generalist` (it already implements `ConsultingService`). The CLI awaits `HealthSystem`, `admit`s a placeholder patient, calls `consultingService.canonicalize(...)`. Reuses the existing seam + admission.

**(b)** a dedicated `@Component InterventionIntake { Document canonicalize(Document); }` in doctor-core, published independently (no patient admission needed for a pure schema canonicalization). The CLI `awaitService(InterventionIntake.class)`.

Prefer **(b)** — canonicalization needs no patient/EHR/ledger references, so a standalone `@Component` activates without the host publishing a registry/journal (lighter CLI boot). Confirm by checking whether `DefaultHealthSystem` would otherwise demand the journal references the CLI has no reason to publish.

- [ ] **Step 2: Add the raw-facts field keys + coordinate to the seam**

In `WorldGatewayCatalog` add (matching the existing `FIELD_*` javadoc style):

```java
  /** Intervention-request payload: the problem reference slug (checkpoint[/symptom]). */
  public static final String FIELD_PROBLEM = "problem";
  /** Intervention-request payload: the free-text description of what was done. */
  public static final String FIELD_WHAT = "what";
  /** Intervention-request payload: the provenance id (defaults to operator-manual). */
  public static final String FIELD_PROVENANCE = "provenance";
  /** Intervention-request payload: the optional remediation-program reference id. */
  public static final String FIELD_PRESCRIPTION_REF = "prescriptionRef";
  /** Intervention-request payload: the ISO-8601 instant the intervention happened. */
  public static final String FIELD_WHEN = "when";
```

In `Coordinate` add `INTERVENTION_REQUEST("intervention-request")` (the raw facts the CLI pushes) and `INTERVENTION("intervention")` (the canonical document `append` persists) — or reuse `INTERVENTION` for both directions if Step 1 chose to validate-in-place. Keep `slug()`/`parse()` uniform with the existing constants.

- [ ] **Step 3: Flip `InterventionLedgerWriter.append`**

`InterventionLedgerWriter.java`: `void append(Document intervention);` — change the import from `…doctor.records.Intervention` to `…world.gateway.port.Document`; update the javadoc.

- [ ] **Step 4: Implement the OSGi canonicalize verb**

In doctor-core (per Step 1's choice), parse the `INTERVENTION_REQUEST` Document's raw-string fields with the doctor schema and build the `Intervention`, returning a canonical `INTERVENTION` Document carrying `intervention.toOutputMap()` as its payload. On an unparseable `FIELD_PROBLEM`/`FIELD_PROVENANCE`/`FIELD_PRESCRIPTION_REF`, return an error Document (a `FIELD_REASON`-bearing verdict) rather than throwing across the seam — the CLI maps it to exit code 2. The `Intervention`/`ProblemRef`/`Provenance`/`RemediationProgramRef` types stay entirely inside this method.

- [ ] **Step 5: Update `PulumiInterventionLedgerWriter.append(Document)`**

Deserialize `document.payload()` (host jackson) to `Map<String,Object>` and pass it straight to `new InterventionResource(RESOURCE_NAME, payloadMap)` — the map IS the `toOutputMap()` shape today, so the `InterventionResource` contract is unchanged. Drop `import …doctor.records.Intervention;`. Keep `StackCoordinate` for now (zone-3b moves it host-internal — it already IS host-internal in pulumi-edge, only its package moves).

- [ ] **Step 6: Update `DriftSpecialist` to append a Document**

Its `InterventionLedgerWriter writer` now wants a Document. Where it calls `writer.append(intervention)`, wrap: `writer.append(interventionDocument(intervention))` with a private helper building an `INTERVENTION` Document from `intervention.toOutputMap()` (serialize with doctor-core's jackson — `Generalist` already has the `mapper`/`serialize` pattern; DriftSpecialist may need its own `ObjectMapper`). `Intervention`/`toOutputMap` stay OSGi-internal.

- [ ] **Step 7: Rewrite `RecordInterventionCommand` (Option A boot)**

`record(String[] args, Instant now, <canonicalize handle>, InterventionLedgerWriter writer)` parses argv to raw strings (keep the flat well-formedness checks — required flags, ISO `--when`), builds an `INTERVENTION_REQUEST` Document (host jackson), calls the canonicalize verb, and on success `writer.append(canonical)`. `main` boots `BootPipeline.embedded().during("record-intervention", <CanonicalizeService>.class, svc -> …)`, builds the host writer (`new PulumiInterventionLedgerWriter(backend)`), registers it if the verb's `@Component` needs it (it should NOT, per Step 1b), and runs `record(...)`. Drop all four `doctor.records` imports. Map an error Document to `System.exit(2)`.

⚠️ The CLI now requires the embedded bundles on its classpath (`BootPipeline.hasEmbeddedBundles()`), exactly like the main bootstrap exec-jar. Confirm `seed-master`'s packaging already stages `META-INF/bundles/` (it does — `BootstrapStage` uses `BootPipeline.embedded()`); the CLI shares that jar.

- [ ] **Step 8: Update the write-path tests**

`RecordInterventionCommandTest` now drives `record(...)` with a fake canonicalize handle + a `List<Document>`-capturing writer; assert the appended Document's payload parses to the expected fields. `PulumiInterventionLedgerWriterLiveTest`/`InterventionLedgerRoundTripLiveTest` build a Document (not an Intervention) to append. Drop their `doctor.records` imports where they only built the input.

- [ ] **Step 9: Full reactor build — expect green + worklist shrink**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | tee /tmp/2c-zone3a.log | grep -iE "BUILD (SUCCESS|FAILURE)|realm.bound|Tests run:|cannot find symbol|LinkageError|unsatisfied" | tail -40
```

Expected: `BUILD SUCCESS`; N drops by `RecordInterventionCommand`, `RecordInterventionCommand$Args`, `PulumiInterventionLedgerWriter`, `InterventionLedgerWriter`. Doctor in-container tests green (they prove the new canonicalize `@Component` activates and the `append(Document)` seam resolves in-container). Record N.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(world-exchange): 2C zone-3a — append(Document) + CLI canonicalizes through OSGi

The write port flips record→Document. RecordInterventionCommand (Option A) boots
the embedded framework and calls an OSGi canonicalize verb that owns the
intervention schema, then appends the canonical Document — the host holds no doctor
type. DriftSpecialist wraps its inferred intervention as a Document before append.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3b (zone-3, read path): the two journals, the registry `@Component`, the readers move OSGi-side

The architectural core. Today the host implements `MedicalRecordRegistry.recordFor(Patient)→MedicalRecord` (the leak: a bundle record crosses host→OSGi) using the flat `MedicalRecordReader` + sub-readers. After this task: the host provides two opaque-blob READ journals, the readers move into doctor-core (Layer-2, behind the journals), `MedicalRecordRegistry` becomes an OSGi `@Component` that folds blobs→record internally, `SnapshotView`/`SnapshotEntry`/`SnapshotSource`/`StackCoordinate` become host-internal (Layer-1, pulumi-edge), and `DriftReview`/`MedicalRecordDump` shed their doctor types. No `doctor.records` type crosses any longer.

**Design resolutions baked in (both faithful to the spec, see Global Constraints):** (1) two focused read ports keyed as decided — `MedicalRecordJournal.historyOf(Patient)`, `InterventionJournal.entries()`; (2) the review verb is `ConsultingService.reviewDrift()` **no-arg** — the spec's `reviewDrift(Checkpoint)` carries a vestigial parameter (the trace takes the patient from `access` and the ledger from the journal; nothing reads the checkpoint), so `DriftReview` (host) is absorbed into the verb + its single call site.

**Files — new seam types (doctor-port):**
- Create: `osgi/domains/doctor/doctor-port/src/main/java/io/nxmatic/rke2lab/doctor/port/MedicalRecordJournal.java` — `interface { List<Document> historyOf(Patient patient); }`
- Create: `…/doctor/port/InterventionJournal.java` — `interface { List<Document> entries(); }`

**Files — readers + registry move doctor-port (flat seam) → doctor-core (bundle, `.internal`):**
- Move (git mv): `ConsultationReportReader.java`, `ExpectationReader.java`, `InterventionReader.java`, `MedicalRecordReader.java`, `MedicalRecordRegistry.java`, `MedicalRecordReconstructionException.java` from `doctor-port/.../doctor/port/` → `doctor-core/.../doctor/internal/` (package `io.nxmatic.rke2lab.doctor.internal`); they keep referencing `doctor.records` but now from INSIDE the bundle realm (legal). The readers' record-rebuild logic is unchanged; only their package + the source of their input changes (from `SnapshotSource`/`SnapshotView` to a `Document`'s parsed blobs).
- Delete (host, flat): `SnapshotSource.java` leaves the seam — it becomes host-internal (next bullet).

**Files — Layer-1 becomes host-internal (pulumi-edge):**
- Move: `SnapshotView.java`, `SnapshotEntry.java`, `StackCoordinate.java` (doctor-records) + `SnapshotSource.java` (doctor-port) → `host/pulumi/pulumi-edge/src/main/java/io/nxmatic/rke2lab/pulumi/edge/` (package `io.nxmatic.rke2lab.pulumi.edge`). They are stack vocabulary, host-only (the StackCoordinate inverse-of-identity case). Update `StackHandleSnapshotSource`, `InterventionLedgerSource`, `InterventionLedgerLayout` references (same package now → drop the imports).

**Files — host journal impls (pulumi-edge):**
- Create: `…/pulumi/edge/StackMedicalRecordJournal.java` implements `MedicalRecordJournal` — folds the patient's stack timeline (the Layer-1 half of today's `MedicalRecordReader.read`: walk `SnapshotSource.timeline()`, for each `SnapshotEntry` harvest `snapshot.outputsNamed(ConsultationReport.OUTPUT_KEY)` + `outputsNamed(Expectation.OUTPUT_KEY)` as RAW blobs) into one `Document` per entry (payload = `{version, when, consultationReport:[…blobs], expectations:[…blobs]}`, coordinate a new `Coordinate.VISIT`). Replaces `LiveMedicalRecordRegistry`'s reconstruct half.
- Create: `…/pulumi/edge/StackInterventionJournal.java` implements `InterventionJournal` — the Layer-1 half of today's `InterventionLedgerSource.load`: walk the ledger stack history, yield one `Document` per entry (payload = the raw `interventions` output blob). Replaces `InterventionLedgerSource`'s read half.
- Delete: `LiveMedicalRecordRegistry.java` (its caching/cohort enumeration moves to the OSGi registry `@Component`; its backend/timeline read becomes `StackMedicalRecordJournal`). Keep `backendDir()` discovery — move it to a small host helper or onto the journal.

**Files — OSGi registry `@Component` (doctor-core):**
- Create: `…/doctor/internal/JournalMedicalRecordRegistry.java` — `@Component(service = MedicalRecordRegistry.class)`, `@Reference MedicalRecordJournal journal`, implements `recordFor(Patient)` by folding `journal.historyOf(patient)` → `MedicalRecord` (parse each VISIT Document's blobs with the moved `ConsultationReportReader`/`ExpectationReader`, build `Visit`, assemble `MedicalRecord`; the caching + `cohortFor` logic comes from `LiveMedicalRecordRegistry`). The cohort enumeration (siblings under the backend) is Layer-1 → expose it via the journal (`MedicalRecordJournal.cohort(Patient)→List<Patient>` or fold it into `historyOf`); decide from the code (simplest: add `List<Patient> cohort(Patient)` to the journal).

**Files — ledger rebuild OSGi-side (doctor-core):**
- The moved `InterventionReader` now folds `InterventionJournal.entries()` → `InterventionLedger` inside the bundle. Add a small `@Component` or internal helper that `@Reference`s `InterventionJournal` and rebuilds the `InterventionLedger` for `Generalist.reviewDrift()`.

**Files — the review verb + host trigger:**
- Modify: `ConsultingService.java` — drop `recordForCurrentPatient()` + `reviewOpenProblems(MedicalRecord, InterventionLedger)`; add `void reviewDrift()`. Drop the `MedicalRecord`/`InterventionLedger`/`ReferralReply` imports → the seam stops importing those records.
- Modify: `Generalist.java` — implement `reviewDrift()`: `record = access.record()` (internal), `ledger = <rebuild from InterventionJournal>` (internal), run the existing `reviewOpenProblems` body, persist via `append(Document)`. `recordForCurrentPatient`/`reviewOpenProblems` become private (or stay on `ClinicalReasoning` if still referenced internally).
- Modify: `DefaultHealthSystem.java`/`ConsultationDag.java` — `@Reference`/thread `MedicalRecordJournal` + `InterventionJournal` instead of the host-published `MedicalRecordRegistry`; the registry is now the internal `@Component`. Confirm the SCR graph: host publishes the two journals + the Document-writer; `JournalMedicalRecordRegistry` + `DefaultHealthSystem` activate OSGi-side.
- Delete: `exec/seed-master/.../bdd/DriftReview.java` — absorbed. Its call site `BootstrapPipeline.admitPatient` becomes `state.doctor.reviewDrift();` (drop the `new DriftReview(backendDir).reviewAtReconstruction(...)`).

**Files — host wiring flip (the leak's origin):**
- Modify: `exec/seed-master/.../pipeline/BootstrapPipeline.java` `admitPatient` (lines ~166-186) — build `StackMedicalRecordJournal` + `StackInterventionJournal` + `PulumiInterventionLedgerWriter` (Document) from `backendDir`; `registerService(MedicalRecordJournal.class, …)`, `registerService(InterventionJournal.class, …)`, `registerService(InterventionLedgerWriter.class, …)`; drop the `LiveMedicalRecordRegistry`/`MedicalRecordRegistry`/`PulumiInterventionLedgerWriter(record)` imports + the `MedicalRecord` references; `state.doctor = healthSystem.admit(currentPatient(...))` unchanged; then `state.doctor.reviewDrift();`.

**Files — `MedicalRecordDump` host-pure (egress):**
- Modify: `exec/seed-master/.../bdd/MedicalRecordDump.java` — read the timeline via `StackMedicalRecordJournal.historyOf(patient)` → `List<Document>`, transcode each VISIT Document's `consultationReport` blob JSON→YAML (host jackson; the stored blob IS the `toOutputMap` shape, so the YAML is byte-identical to today's reconstruct-then-reserialize). Drop `MedicalRecordReader`/`MedicalRecord`/`ConsultationReport`/`Patient`→ keep `Patient` (seam now). No OSGi call. The `Result`/policy stays; `MedicalRecordReconstructionException` is gone host-side (the journal surfaces read failures as host-internal).

**Interfaces:**

- Consumes: zone-1 `Patient`/`Checkpoint` seam; zone-3a `append(Document)`.
- Produces: `MedicalRecordJournal.historyOf(Patient)→List<Document>` + `InterventionJournal.entries()→List<Document>` (host-published); `ConsultingService.reviewDrift()`; `Coordinate.VISIT`. doctor-port no longer references any `doctor.records` record (only `Patient`/`Checkpoint` seam identities + `Document`). zone-5 consumes the empty worklist.

- [ ] **Step 1: Create the two journal seam interfaces**

Create `MedicalRecordJournal` and `InterventionJournal` in `doctor-port` (package `io.nxmatic.rke2lab.doctor.port`), importing `io.nxmatic.rke2lab.world.gateway.port.Document` + `…port.Patient`. Add the cohort accessor decided above to `MedicalRecordJournal`. Javadoc each as the Layer-1 host port the OSGi rebuild reads through.

- [ ] **Step 2: Add `Coordinate.VISIT` + the visit field keys to the seam**

In `Coordinate` add `VISIT("visit")`. In `WorldGatewayCatalog` add `FIELD_VERSION`/`FIELD_WHEN` if not present (the visit Document carries version+when+the existing `FIELD_CONSULTATION_REPORT`/`FIELD_EXPECTATIONS` blob lists). Reuse `FIELD_CONSULTATION_REPORT`/`FIELD_EXPECTATIONS` (already defined).

- [ ] **Step 3: Move SnapshotView/SnapshotEntry/StackCoordinate + SnapshotSource host-internal**

`git mv` the four files to `host/pulumi/pulumi-edge/.../pulumi/edge/`, rewrite their `package` to `io.nxmatic.rke2lab.pulumi.edge`, and update `StackHandleSnapshotSource`/`InterventionLedgerSource`/`InterventionLedgerLayout` (drop the now-same-package imports). Verify no OSGi code references `SnapshotSource`/`SnapshotView`/`SnapshotEntry`/`StackCoordinate` (the grep from mapping confirmed: only host + the doctor-port `StackCoordinateTest` which moves with it). Move `StackCoordinateTest`/`StackCheckpointTest` too.

- [ ] **Step 4: Move the readers + registry + exception into doctor-core `.internal`**

`git mv` `ConsultationReportReader`, `ExpectationReader`, `InterventionReader`, `MedicalRecordReader`, `MedicalRecordRegistry`, `MedicalRecordReconstructionException` to `doctor-core/.../doctor/internal/`, rewrite each `package` to `io.nxmatic.rke2lab.doctor.internal`, and update every importer. `MedicalRecordReader`'s SnapshotSource-folding half is rewritten in Step 6 (here just relocate + repackage so the bundle owns them). doctor-core `bnd.bnd` already seals `.internal` (NOT exported) — confirm these don't need exporting (they don't; they're behind the registry `@Component`).

- [ ] **Step 5: Write the host journal impls + delete LiveMedicalRecordRegistry/InterventionLedgerSource read half**

Create `StackMedicalRecordJournal` (timeline→`List<Document>` of VISIT docs, from `LiveMedicalRecordRegistry.reconstruct` + `MedicalRecordReader.read`'s Layer-1 half + the cohort `siblings` enumeration) and `StackInterventionJournal` (ledger history→`List<Document>`, from `InterventionLedgerSource.load`'s Layer-1 half). Delete `LiveMedicalRecordRegistry` and the read half of `InterventionLedgerSource` (its class may be deleted entirely if nothing else uses it). Preserve `backendDir` discovery (`fromEnvironment`/`backendDirFromUrl`).

- [ ] **Step 6: Write the OSGi registry `@Component` + ledger rebuild**

Create `JournalMedicalRecordRegistry` (`@Component(service = MedicalRecordRegistry.class)`, `@Reference MedicalRecordJournal`) folding VISIT Documents → `MedicalRecord` via the moved readers (parse each Document's blob lists, build `Visit(version, when, reports, expectations)`). Add the ledger rebuild (fold `InterventionJournal.entries()` → `InterventionLedger` via the moved `InterventionReader`) — either a second `@Component` or a helper `Generalist` calls. Port the cache + cohort logic.

- [ ] **Step 7: Collapse the seam verbs + Generalist + wiring**

`ConsultingService`: drop the two record verbs, add `void reviewDrift()`, drop the record imports. `Generalist.reviewDrift()`: rebuild record (`access.record()`) + ledger (from the journal rebuild) internally, run the `reviewOpenProblems` body, `append(Document)`. `DefaultHealthSystem`/`ConsultationDag`: reference the journals + the internal registry; confirm the activation graph. Delete `DriftReview`; `BootstrapPipeline.admitPatient` registers the two journals + the writer and calls `state.doctor.reviewDrift()`.

- [ ] **Step 8: Rewrite MedicalRecordDump host-pure + fix all tests**

`MedicalRecordDump`: transcode VISIT Documents' `consultationReport` blobs JSON→YAML, no reader, no OSGi. Then fix every test the moves touched: doctor-port tests for the moved readers/registry move to doctor-core-test (or doctor-port-test → doctor-core-test as appropriate); host tests (`SeededMedicalHistoryTest`, `LiveMedicalRecordRegistryCohortTest`, `ConsultationReportReaderTest`, `DriftReviewReconstructionLiveTest`, `MedicalRecordDumpTest`) re-point at the journals / drop the deleted classes. This is the largest test-fix step — enumerate with the grep below.

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -rln "MedicalRecordReader\|MedicalRecordRegistry\|ConsultationReportReader\|ExpectationReader\|InterventionReader\|MedicalRecordReconstructionException\|SnapshotSource\|LiveMedicalRecordRegistry\|InterventionLedgerSource\|DriftReview\|recordForCurrentPatient\|reviewOpenProblems" --include="*.java" . | grep -v /target/
```

- [ ] **Step 9: Confirm the realm is clean (the whole point)**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
echo "--- any flat/host doctor.records reference left (expect ONLY OSGi-internal) ---"
grep -rn "import io\.nxmatic\.rke2lab\.doctor\.records" --include="*.java" exec/seed-master/src host/pulumi/pulumi-edge/src | grep -v /target/
```

Expected: empty (host holds zero `doctor.records` import). doctor-port references only `Patient`/`Checkpoint` (seam) + `Document` — no record.

- [ ] **Step 10: Full reactor build — expect green + worklist near-zero**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | tee /tmp/2c-zone3b.log | grep -iE "BUILD (SUCCESS|FAILURE)|realm.bound|Tests run:|cannot find symbol|LinkageError|unsatisfied|did not activate" | tail -50
```

Expected: `BUILD SUCCESS`; `realm-boundary` N collapses to just the `ClusterSchemaRef` isolate (zone-4) — i.e. **N == 1** (or only `cluster-port`'s `ClusterSchemaRef`). Doctor in-container tests green — they PROVE the new `@Component` graph activates (the host-published journals satisfy `JournalMedicalRecordRegistry`, which satisfies `DefaultHealthSystem`) and that no `doctor.records` type LinkageErrors across the realm. If a service "did not activate", a `@Reference` is unbound — check the host registered all three. Record N.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat(world-exchange): 2C zone-3b — the peer node, reconstruction crosses as opaque Documents

Two host read journals (MedicalRecordJournal.historyOf(Patient),
InterventionJournal.entries()) yield opaque List<Document>; the readers move into
doctor-core behind them; MedicalRecordRegistry becomes an OSGi @Component folding
blobs→record internally; SnapshotView/Entry/Source/StackCoordinate become
host-internal (pulumi-edge); reviewDrift() collapses the two record verbs; DriftReview
is absorbed; MedicalRecordDump goes host-pure. No doctor.records type crosses.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4 (zone-4): the cross-domain isolate — `ClusterSchemaRef` stops referencing `doctor.records.SchemaRef`

The last worklist entry. `cluster-port`'s `ClusterSchemaRef` wraps `doctor.records.SchemaRef` (`SchemaRef.of("cluster/kubeconfig/v1")` etc.) — a flat seam (`cluster-port` is `type=seam`) referencing a `type=record` bundle type = the leak. `SchemaRef` is a tiny value record (`String id` + `of`/`parse`); it is doctor vocabulary that cluster shouldn't depend on at the seam.

**Decision (resolve the one cross-domain coupling):** `ClusterSchemaRef` holds a plain `String id()` at the seam (it is just a schema-id registry); the conversion to a doctor `SchemaRef` happens OSGi-side, where `ClusterSpecialist` already builds the `Assessment`. `ClusterSpecialist` (cluster-core, a bundle) calls `SchemaRef.of(clusterSchemaRef.id())` when assembling the `Assessment` — moving the one `SchemaRef` reference from the flat seam into the bundle realm (legal, like the readers in zone-3b).

**Files:**
- Modify: `osgi/domains/cluster/cluster-port/src/main/java/io/nxmatic/rke2lab/cluster/port/ClusterSchemaRef.java` — drop `import …doctor.records.SchemaRef;`; the enum holds `private final String id;`, `public String id() { return id; }` (the constructor takes the id string as today; just stop wrapping it in `SchemaRef`)
- Modify: `osgi/domains/cluster/cluster-core/src/main/java/io/nxmatic/rke2lab/cluster/internal/ClusterSpecialist.java` — where it calls `ClusterSchemaRef.KUBECONFIG.ref()`, call `SchemaRef.of(ClusterSchemaRef.KUBECONFIG.id())` (import `…doctor.records.SchemaRef` here — cluster-core is a bundle, the reference is legal)
- Modify: any test referencing `ClusterSchemaRef.*.ref()` → `.id()` / wrap as needed

**Interfaces:**

- Consumes: zone-3b's clean worklist (only this isolate remains).
- Produces: `ClusterSchemaRef.id() : String` (no doctor type on the cluster seam). Worklist == 0. zone-5 flips the gate.

- [ ] **Step 1: Drop `SchemaRef` from `ClusterSchemaRef`**

Replace the `private final SchemaRef ref;` + `ref()` with `private final String id;` + `id()`; the constructor already receives the id string — stop the `SchemaRef.of(id)` wrap. Remove the import and update the `ref()` javadoc (it references `Assessment`).

- [ ] **Step 2: Convert in `ClusterSpecialist` (bundle-side)**

At each `ClusterSchemaRef.X.ref()` call site (4 of them — KUBECONFIG/CONTROLLER/API/OTHER), change to `SchemaRef.of(ClusterSchemaRef.X.id())`. Add `import io.nxmatic.rke2lab.doctor.records.SchemaRef;` to `ClusterSpecialist` (cluster-core is a bundle — legal). Verify cluster-core already deps `doctor-records` (it does — confirmed in mapping).

- [ ] **Step 3: Fix any `.ref()` caller**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -rn "ClusterSchemaRef\.[A-Z_]*\.ref()\|ClusterSchemaRef.*SchemaRef" --include="*.java" . | grep -v /target/
```

Update any remaining caller to `.id()` (+ wrap in `SchemaRef.of` if it needs the doctor type, bundle-side only).

- [ ] **Step 4: Confirm the worklist is ZERO**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
echo "--- any flat/seam reference to doctor.records left anywhere outside doctor-core/doctor-records ---"
grep -rn "import io\.nxmatic\.rke2lab\.doctor\.records" --include="*.java" . | grep -v /target/ | grep -vE "osgi/domains/doctor/(doctor-core|doctor-records|doctor-spi|doctor-core-test|doctor-port-test)/"
```

Expected: empty — no flat/host/cross-domain-seam class references `doctor.records`.

- [ ] **Step 5: Full reactor build — expect worklist 0**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | tee /tmp/2c-zone4.log | grep -iE "BUILD (SUCCESS|FAILURE)|realm.bound|Tests run:" | tail -30
```

Expected: `BUILD SUCCESS`; `realm-boundary: 0 error, 0 warn`. If any warn remains, a class was missed — the gate names it; resolve before zone-5.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(world-exchange): 2C zone-4 — ClusterSchemaRef drops the doctor.records.SchemaRef seam reference

The one cross-domain isolate: the cluster seam holds a plain String id; the
conversion to a doctor SchemaRef moves bundle-side into ClusterSpecialist. The
REALM_BOUNDARY worklist reaches zero.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5 (zone-5): the flip — `REALM_BOUNDARY` WARN→ERROR (the merge point)

The worklist is zero; remove the three `@GovernedBy(REALM_BOUNDARY, WARN)` overrides so the packages return to the locked `ERROR` default. The boundary is now build-enforced: any future flat class that references `doctor.records` fails the build. This is the static half of the merge gate (the dynamic half is the remote-validation capstone, increment 4).

**Files:**
- Modify: `osgi/domains/cluster/cluster-port/src/main/java/io/nxmatic/rke2lab/cluster/port/package-info.java` — remove the `@GovernedBy(REALM_BOUNDARY, WARN)` line + its explanatory comment
- Modify: `osgi/domains/doctor/doctor-port/src/main/java/io/nxmatic/rke2lab/doctor/port/package-info.java` — same
- Modify: `exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/package-info.java` — same

**Interfaces:**

- Consumes: zone-4's zero worklist.
- Produces: `REALM_BOUNDARY` at `ERROR` everywhere — the build now fails on any reintroduced leak. The static separation lock.

- [ ] **Step 1: Remove the three WARN overrides**

In each `package-info.java`, delete the `@GovernedBy(value = StagingGate.REALM_BOUNDARY, level = EnforcementLevel.WARN)` annotation and its leading explanatory comment. If a package has OTHER `@GovernedBy` annotations (e.g. SPEC_COVERAGE), keep those — remove ONLY the REALM_BOUNDARY one. If removing it leaves an unused import (`EnforcementLevel`, `StagingGate`), remove the import too (but check other annotations don't still need them).

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -n "REALM_BOUNDARY\|GovernedBy\|EnforcementLevel\|import" \
  osgi/domains/cluster/cluster-port/src/main/java/io/nxmatic/rke2lab/cluster/port/package-info.java \
  osgi/domains/doctor/doctor-port/src/main/java/io/nxmatic/rke2lab/doctor/port/package-info.java \
  exec/seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/package-info.java
```

Edit each by hand (package-info is tiny) so the unused-import cleanup is precise.

- [ ] **Step 2: Confirm no REALM_BOUNDARY WARN governance remains**

```bash
cd /private/var/lib/git/nxmatic/rke2lab.d/feature/cluster-edge
grep -rn "REALM_BOUNDARY" --include=package-info.java . | grep -v /target/
```

Expected: empty (no package overrides REALM_BOUNDARY any longer — it is the ERROR default everywhere).

- [ ] **Step 3: Full reactor build — the lock holds (green at ERROR)**

```bash
flox activate -- ./mvnw clean package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true 2>&1 | tee /tmp/2c-zone5.log | grep -iE "BUILD (SUCCESS|FAILURE)|realm.bound|Tests run:" | tail -30
```

Expected: `BUILD SUCCESS` with `realm-boundary: 0 error` and NO warn line (the gate is now ERROR-default and the worklist is empty, so it passes silently). The build succeeding AT ERROR is the proof the two realms are statically separated. (To prove the lock bites, optionally re-add one `doctor.records` import to a host class on a scratch commit and confirm the build now FAILS — then revert; do this only as a manual sanity check, not in the committed history.)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(world-exchange): 2C zone-5 — flip REALM_BOUNDARY WARN→ERROR, the static separation lock

The worklist is zero; the three WARN overrides are removed so REALM_BOUNDARY
returns to its ERROR default. Any future flat class referencing doctor.records now
fails the build. The host and OSGi realms are provably separated, statically. This
is the merge point's static half (the remote-validation capstone is its dynamic half).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage** (against `world-exchange-2c-reconstruction-path-spec.adoc`):
- The peer model (two peers, bidirectional, opaque Documents) → zone-3a (write) + zone-3b (read) realize all three ports. ✓
- The reader two-layer split (Layer-1 host / Layer-2 OSGi) → zone-3b Steps 3-6. ✓
- The three host-provided ports flip record→Document → `append(Document)` (3a), `MedicalRecordJournal`/`InterventionJournal` (3b), `reviewDrift()` (3b). ✓ (with the resolved corrections: two read ports not one; `reviewDrift()` no-arg)
- Case 1 `DriftReview` collapse → zone-3b Step 7 (absorbed into `reviewDrift()`). ✓
- Case 2 `MedicalRecordDump` host-pure → zone-3b Step 8. ✓
- Identities to the seam (`Checkpoint`, `Patient`) → zone-1. ✓
- `SnapshotView`/`Entry`/`Source` host-internal → zone-3b Step 3 (+ `StackCoordinate`, the spec-gap addition). ✓
- The 5-zone cut → zones 1-5 (with the documented refinement: CLIs ride zone-3, not a separate late zone; zone-4 is only `ClusterSchemaRef`). ✓
- `RecordInterventionCommand` ingress via OSGi canonicalization → zone-3a (Option A, user decision). ✓
- `ClusterSchemaRef` → zone-4. ✓
- The flip → zone-5. ✓
- zone-2 consult residue (the I-1 finding) → zone-2. ✓

**2. Placeholder scan:** No "TBD"/"handle errors"/"similar to". The judgment points (canonicalize as standalone `@Component` vs on `ConsultingService`; cohort accessor shape; whether `ObservationView` is needed) are each spelled out with a recommended resolution AND the "decide from the code" criterion — they are genuine read-first-then-choose forks, not hidden work. The two design corrections (two read ports; `reviewDrift()` no-arg) are stated explicitly with rationale, both user-adjacent (the read-ports one was user-approved).

**3. Type/path consistency:** `append(Document)` produced by 3a, consumed by 3b's `reviewDrift`. `MedicalRecordJournal.historyOf(Patient)`/`InterventionJournal.entries()` defined 3b Step 1, consumed 3b Step 6. `Patient`/`Checkpoint` seam package `io.nxmatic.rke2lab.world.gateway.port` used consistently from zone-1 on. `Coordinate.VISIT`/`INTERVENTION`/`INTERVENTION_REQUEST` + the `FIELD_*` keys added where first used. The worklist invariant (N strictly decreasing: 38 → <38 → less → less → 1 → 0 → locked) is the cross-zone consistency check.

**Known intentional deviations from the spec (all flagged in Global Constraints):** (a) two read ports instead of `historyOf(Checkpoint)` — user-approved; (b) `StackCoordinate` host-internal — spec gap, the inverse-identity case; (c) CLIs migrate in zone-3 not a late zone-4 — forced by the green-per-task rule; (d) `reviewDrift()` no-arg — vestigial-parameter slip, same family as (a).
