---
name: doctor-internal-edge-debt
description: "DEBT revealed by the internal/external edge model (2026-06-21): the doctor extraction (merged ac2fae1b) closed doctor's EXTERNAL edge (doctor-port SnapshotSource -> pulumi) but left its INTERNAL edge MISSING. 40+ files in exec/seed-master/controlplane import doctor-core impl types directly (Generalist, ConsultationLog, MedicalRecord, Symptom, Checkpoint) as constructor params instead of calling a contract. manifests/netplan are CLEAN (consumers use -port only) — doctor is the smoking gun. Fix = add a DoctorConsultingService internal edge to doctor-port; FIRST internal-edge increment, precedes the external <target>-edge extraction."
metadata:
  node_type: memory
  type: project
---

## What it is

The internal/external edge model ([[system-space-world-universe-glossary]],
`docs/architecture/patterns/frontier-playability-model.adoc`) revealed a debt the doctor extraction
left open. The merge (`ac2fae1b`) lifted the pure model into `osgi/doctor` and closed the EXTERNAL
edge (`doctor-port.SnapshotSource` → `pulumi-edge`). But doctor's INTERNAL edge — its face toward the
rest of our system — is MISSING: `doctor-port` carries only the external face.

## The leakage (the tell)

Pre-analysis over the reactor: 40+ files in `exec/seed-master/.../controlplane` import `doctor-core`
IMPL types directly rather than calling a contract — `Generalist`, `ConsultationLog`, `MedicalRecord`,
`Symptom`, `Checkpoint`, `RemediationPlan`, `Observation` — and pass them as constructor params
(`ResourceManager`, `BootstrapPipeline`, the pipeline stages, `ClusterReadinessResource`,
`SystemdAdapterResource`). Changing `MedicalRecord`'s shape ripples through 20-40 files. By contrast
`manifests-port` / `netplan-port` are CLEAN — `IncusResourceBootstrap` and the CLIs import only `-port`
service types, zero `-core` leakage. Doctor is the smoking gun that proves the internal-edge concept is
prescriptive, not cosmetic.

## The fix (first internal-edge increment)

Add a `DoctorConsultingService` internal edge to `doctor-port`: `consult(symptom, observation) →
RemediationPlan`, plus record/history queries. Hide the impl types (`Generalist`, `ConsultationLog`,
`MedicalRecord`, …) behind it; rewire the ~40 consumers to the contract. Result: doctor becomes
symmetric with manifests/netplan — consumers see a contract, the impl evolves freely. This is a code
increment (its own worktree); it PRECEDES the external `<target>-edge` extraction (the
[[orchestration-purity-benefit]] / edges chantier), because it is the half the merge left unfinished.

## DECISION — Placement 2 (the real membrane), 2026-06-21

The dependency arrow is `doctor-core → doctor-port` (port depends on nothing). So a contract that
speaks the value types (Symptom, Observation, RemediationPlan, MedicalRecord, …, currently in core)
cannot live in doctor-port unless the value types move there too. Two placements were weighed:
- *Placement 1* — keep values in core, put the contract in core's exported package, hide only the
  coordinators in a `…internal` sub-package. Low cost, BUT doctor-port would carry only the EXTERNAL
  edge — the internal edge would have no membrane, contradicting the freshly-graved map.
- *Placement 2 (CHOSEN by the user)* — MIGRATE the cross-boundary value types from doctor-core into
  doctor-port, then put `DoctorConsultingService` there. doctor-port becomes the TRUE membrane (value
  vocabulary + the contract); doctor-core holds the impls and depends on the port. Larger blast radius
  (~17 value types moved, ~40 consumers + the impls rewrite their imports, core inverts its dep), but
  the code finally MATCHES the map. User's rationale: "if the records aren't in the right place, repair
  NOW — otherwise it never happens." Re-entrance: the code resembles the figure.

Two refinements to settle ON THE FACTS before the final plan (a Plan agent is computing them):
1. *Which of the ~17 value types actually CROSS the membrane* (migrate those to doctor-port) vs which
   stay internal to the reasoning (only Generalist/HealthSystem touch them — stay in core). It is a
   SORT, not a wholesale block move.
2. *No back-cycle*: verify no migrated value type depends in return on an impl left in doctor-core
   (else core→port→core cycle). 

The contract surface (4 ops, verified): `consult(symptom, observation)→RemediationPlan`,
`recordForCurrentPatient()→MedicalRecord`, `cohortFinding(symptom)→String`,
`reviewOpenProblems(record, ledger)→List<ReferralReply>`. Graph CONSTRUCTION stays HOST (a
`DoctorAssembly` in seed-master reads BootstrapConfig/env/Pulumi, builds registry+specialists+writer,
returns a `DoctorConsultingService`) — NOT in the port (would re-couple to BootstrapConfig). The SPI
types the host implements (`Specialist`, `MedicalRecordRegistry`, `InterventionLedgerWriter`,
`DriftSpecialist`) STAY public/exported. ~9 tests construct the graph directly → rewire through a test
factory, NO @Deprecated / NO kept old constructor (migration-branch-no-fallback). This is a CODE
increment (own worktree, hand-on), precedes the external `<target>-edge` extraction.

## The MIGRATE/STAY sort (investigated 2026-06-21, the agent was Bash-blocked so done by hand)

doctor-core has 50 types; 47 are imported cross-bundle. The sorting RULE the user gave (the scalpel):
*an interface implemented OUTSIDE doctor-core = a contract/membrane → doctor-port; a `final class`
actor = internal machinery → stays in doctor-core, hidden.* "Is it data/contract, or an actor?"

**MIGRATE → doctor-port (the membrane):**
- *Value records / enums (data that crosses):* Symptom, Observation, MedicalRecord, RemediationPlan,
  Patient, ConsultationReport, Assessment, Prescription, Referral, ReferralReply, Intervention,
  InterventionLedger, Expectation, Severity, Visit, Provenance, SchemaRef, ProblemRef, ProblemReview,
  Checkpoint, ClusterReadinessPhase, ChiefComplaint, Comorbidity, SymptomHistory, TreatmentEfficacy,
  ResolutionPredicate, ExpectationPredicate, ConsultationNarration, StackCoordinate,
  RemediationProgramRef, Specialty, ConsultationLog, MedicalRecordReconstructionException.
- *Contracts (interfaces implemented cross-bundle):* `Specialist` (impl host DbusTcpSpecialist),
  `MedicalRecordRegistry` (impl host LiveMedicalRecordRegistry — read edge-contract),
  `InterventionLedgerWriter` (impl host PulumiInterventionLedgerWriter — the WRITE edge-contract, the
  twin of SnapshotSource per its own javadoc; the user caught this — it is an ACTOR/port, not a data
  record), plus the new `DoctorConsultingService` (the internal-edge contract). Verify `Clinician`
  (interface) — who implements it.

**STAY in doctor-core (hidden actors / coordinators — `final class`):**
- Coordinators: `Generalist`, `HealthSystem`, `ClinicalAccess`, `GrantPolicy`, `Grant`, `ClinicianId`
  (+ `Clinician` if internally implemented). In prod these are imported ONLY by the 8 call-sites the
  contract replaces (Generalist: 6 pipeline/resource files; HealthSystem: BootstrapPipeline +
  PipelineState); everything else is tests → hideable after the rewire.
- Reconstruction machinery (`final class`, the user's correction — NOT contracts): `DriftSpecialist`,
  `MedicalRecordReader`, `ConsultationReportReader`, `InterventionReader`, `ExpectationReader`.
- doctor-core's own Specialist impls: `ClusterSpecialist`, `NetworkSpecialist` (only `new`-ed by the
  host DoctorAssembly + FakeSpecialistsTest — confirm they can stay core or must be port).

## NO-CYCLE PROOF — DONE 2026-06-21 (Placement 2 is sound)

Verified inline (all 58 doctor sources share one package `io.nxmatic.rke2lab.doctor`, so cross-type
refs are NOT imports — proof scans the TYPE NAMES in non-comment code). Result: doctor-port (MIGRATE)
depends on nothing in doctor-core. Method: word-grep each STAY actor across all files, then re-scan
with block+line comments stripped to drop `{@link}` noise.

- *Every word-grep hit of a MIGRATE file naming a STAY type was JAVADOC ONLY* (`{@link Generalist}`,
  prose) — javadoc creates no compile edge. The non-comment scan shows ZERO MIGRATE value type
  (Observation, MedicalRecordRegistry, Specialist, RemediationPlan, Specialty, Symptom, ProblemReview,
  ConsultationReport) referencing a STAY actor in real code.
- *One real code ref surfaced — a MIS-SORT, not a cycle:* `Specialist` (MIGRATE contract) has
  `default ClinicianId clinicianId()` doing `new ClinicianId(...)`. But **`ClinicianId` is a pure value
  record** (typed join key, twin of `Symptom.id()`/`RemediationProgramRef`) and **`Clinician` is just
  `interface { ClinicianId clinicianId(); }`** — `Specialist`'s supertype. CORRECTION: both MIGRATE →
  doctor-port (the earlier STAY classification was wrong). The host implements `Specialist`
  (`DbusTcpSpecialist`) → it needs `Clinician`/`ClinicianId` exported regardless.
- *`Grant` STAYS* despite being a value record (`Grant(ClinicianId, Patient)`): it never crosses the
  membrane — only `GrantPolicy`/`ClinicalAccess` (STAY actors) touch it. A value type migrates only if
  it CROSSES; Grant is buried machinery. (Grant→ClinicianId is core→port, allowed.)

Final arrows: doctor-core (STAY actors: Generalist, HealthSystem, ClinicalAccess, GrantPolicy, Grant,
MedicalRecordReader, ConsultationReportReader, InterventionReader, ExpectationReader, DriftSpecialist,
ClusterSpecialist, NetworkSpecialist) → doctor-port (all MIGRATE value records + Clinician/ClinicianId +
contracts Specialist/MedicalRecordRegistry/InterventionLedgerWriter/DoctorConsultingService). ACYCLIC.

Plan graved: `.claude/plans/doctor-internal-edge-placement2-plan.md`.

## CHANTIER B — doctor tests go full-OSGi: DONE 2026-06-21 (commit eee828c6)

The last step that makes Placement 2 coherent with its world. doctor's tests now run IN the OSGi
container via `-test` fragments, not the flat classpath. `doctor-port-test` (34 in-container) carries
the 5 un-parked value-type tests + the fixtures (exported); `doctor-core-test` (29 in-container: 8
white-box actor tests + 2 jGiven scenarios) reaches the sealed package-private actors. The
`doctor-testkit` module is DELETED (dissolved into doctor-port-test) → the `doctor-port ↔
doctor-testkit` cycle is GONE. `osgi/testkit`→`osgi/junit-testkit` (the generic in-container runner).
Build green; 0 value-type test parked in HOST. The reusable model: [[junit-in-osgi-test-fragment-model]].
Handoff: `docs/architecture/doctor/placement2-osgi-tests-handoff.adoc`. INTEGRATION ON MAIN PENDING
(the user's gesture).

NOTE: the background Plan agent (a7aea786) was BLOCKED — background agents run with Bash denied, so the
fact-based sort was done in the main thread. Do NOT relaunch it.

See [[system-space-world-universe-glossary]] (the edge species),
`docs/architecture/patterns/frontier-playability-model.adoc` (the model + the doctor smoking-gun
worked example).
