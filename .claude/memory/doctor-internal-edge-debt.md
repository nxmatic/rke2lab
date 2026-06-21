---
name: doctor-internal-edge-debt
description: "SHIPPED (parked, not integrated) 2026-06-21 on branch refactor/doctor-internal-edge: Placement 2 closed doctor's INTERNAL edge. doctor-port is now the TRUE membrane (value vocabulary + contracts + the new DoctorConsultingService); doctor-core holds 8 package-private actors and depends on the port. doctor is symmetric with manifests/netplan — compiler-enforced, build green 254 tests. THREE facts proven on the code: readers are read-edges (→ port, not hidden actors); package-private (not bnd Export-Package) IS the membrane mechanism; the ReferralReplies Maven cycle is a 2nd, independent motivation for the OSGi test-fragment model. PARKED pending the test-fragment chantier (dissolves the one accepted debt + rehomes 5 HOST tests)."
metadata:
  node_type: memory
  type: project
---

## What it was, why it mattered

The internal/external edge model (`docs/architecture/patterns/frontier-playability-model.adoc`,
[[system-space-world-universe-glossary]]) revealed that the doctor extraction (`ac2fae1b`) closed
doctor's EXTERNAL edge (`SnapshotSource`→pulumi) but left its INTERNAL edge MISSING: 40+ controlplane
files imported `doctor-core` impl types directly. doctor was the smoking gun proving the internal-edge
concept is PRESCRIPTIVE — its value is the leakage its absence allows. manifests/netplan were the clean
reference (consumers cross `-port` only).

**Placement 2** (the user's call, against Placement 1): make `doctor-port` the TRUE membrane — migrate
the value vocabulary INTO it, not just put a contract in core. Rationale: "if the records aren't in the
right place, repair NOW — otherwise never." The code must resemble the figure (re-entrance).

## SHIPPED 2026-06-21 — branch refactor/doctor-internal-edge (PARKED, not integrated)

`DoctorConsultingService` (4 ops: consult / recordForCurrentPatient / cohortFinding /
reviewOpenProblems) is the internal edge in doctor-port. The membrane carries the value vocabulary +
contracts + the readers; doctor-core holds 8 package-private actors and depends on the port (acyclic).
Two public assembly seams return the contract without exposing actors: `Doctor.consultingService(...)`
(prod, prepends the Network+Cluster roster) and `ExactRosterDoctor.over(...)` (test). Build green: 254
tests, 19 modules, zero actor imported outside `io.nxmatic.rke2lab.doctor` (compiler-enforced).

**Why PARKED, not integrated:** the work is "done" only when the test modules go OSGi (fragment-tests),
which rehomes the 5 HOST-parked tests and dissolves the ReferralReplies debt below. We don't integrate a
half. Gated on the jgiven-osgi spike verdict ([[osgi-testkit-framework-injection-idea]]).

## Three facts proven on the code (the durable learnings)

1. **The readers are read-edges, not hidden actors.** `MedicalRecordReader`, `ConsultationReportReader`,
   `InterventionReader`, `ExpectationReader` take port types → return port types, touch NO actor, and
   have legitimate host production consumers (LiveMedicalRecordRegistry, MedicalRecordDump,
   InterventionLedgerSource read Pulumi / the file backend). They are the read-side twins of
   `SnapshotSource`/`InterventionLedgerWriter` → they MIGRATE to doctor-port and stay public. The
   hide-list dropped 12→8. *A read-edge belongs to the membrane.*

2. **package-private IS the membrane mechanism — NOT bnd Export-Package.** bnd narrowing governs OSGi
   resolution between bundles, not the Maven compile classpath. Dropping `public` from the 8 actors (all
   in one package) makes the Java COMPILER forbid cross-module import (compile + OSGi at once), while
   co-located doctor-core tests keep white-box. "Public interface, package-private impl." This is why
   sealing strictly required the model-tests to be relocated INTO doctor-core first. Reusable for every
   future internal edge.

3. **The ReferralReplies Maven cycle = a 2nd, independent motivation for the test-fragment model.**
   A shared test fixture that builds a port type must be its own module (`doctor-testkit`→doctor-port),
   but it's also used by doctor-port's own tests → Maven rejects `doctor-port ↔ doctor-testkit`
   (module-level, scope-blind). Root cause: our tests run in the bare-JVM flat classpath yet cover code
   that lives in classloader-isolated bundles. In OSGi this is NOT a cycle — a test FRAGMENT *is* its
   host (shared classloader), fixture visible with no reverse edge. Accepted debt: 5 value-type tests
   stay in HOST (seed-master) instead of doctor-port; zero duplication, zero masking. Dissolves for free
   when the fragment chantier lands.

## The 8 sealed actors (durable list)

`Generalist`, `HealthSystem`, `ClinicalAccess`, `GrantPolicy`, `Grant`, `DriftSpecialist`,
`NetworkSpecialist`, `ClusterSpecialist` — all `final class`/`record`, package-private. (`Clinician`/
`ClinicianId` corrected mid-flight from STAY to MIGRATE: pure value/contract, not actors. `Grant` stays
core: a value record that never crosses the membrane.)

Handoff + measured 52-file test classification: branch `refactor/doctor-internal-edge`,
`docs/architecture/doctor/placement2-handoff.adoc`. See [[orchestration-purity-benefit]] (the OSGi
orchestration chantier the external `<target>-edge` extraction belongs to, next after integration).
