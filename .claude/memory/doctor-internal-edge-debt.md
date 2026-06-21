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

See [[system-space-world-universe-glossary]] (the edge species),
`docs/architecture/patterns/frontier-playability-model.adoc` (the model + the doctor smoking-gun
worked example).
