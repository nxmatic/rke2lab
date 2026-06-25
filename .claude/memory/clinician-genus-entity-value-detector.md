---
name: clinician-genus-entity-value-detector
description: "The doctor vocabulary made into model (settled brainstorm 2026-06-25): Clinician is the GENUS (identity + an operation model); its species are diagnostician (Specialist: assess+prescribe) and remediator (administers). The entity-vs-value detector + the per-role port-exposure gradient."
metadata:
  node_type: memory
  type: project
---

A 2026-06-25 brainstorm (during the specialist-distribution increment) settled the doctor
vocabulary as MODEL, by challenging a naming reflex until the real axis surfaced. Builds on
[[doctor-remediation-model]] (the Remediator was already designed there) and
[[object-graph-navigability-principle]].

**Name by MEANING, never by Java role.** The `HealthSystem` is `HealthSystem`, not
`HealthSystemService`; `Patient` is `Patient`, not `PatientRecord`. A `Service`/`Record` suffix only
ever names a HALF of something, and "going object" means data + operations live together in one named
type. The interface/impl split (`HealthSystem` port iface ↔ `DefaultHealthSystem` `.internal` impl)
is NOT the data/ops split — it is the WORLD-BOUNDARY axis (contract crosses the OSGi seam,
system-exported; the object's data stays sealed bundle-side; `Default` is the codebase's existing
impl-disambiguation idiom, cf. `DefaultManifestSynthesisService`).

**The entity-vs-value detector (the corrected axis — NOT data/ops, NOT state/effect).** A first
wrong cut said "record = data-only, object = data+ops". `MedicalRecord` falsifies it: it is a
`record` SATURATED with operations (`efficacyOf`, `historyOf`, `comorbiditiesWith`) yet is plainly a
value. The axis that actually classifies:

- **Value** (record): identity BY VALUE (two equal `MedicalRecord`s are the same); its operations are
  pure SELF-PROJECTIONS — they reveal its own immutable content (a fold over its own fields).
  `MedicalRecord`, `InterventionLedger`, `Assessment`, `Prescription`, `Patient`, `Observation`,
  `RemediationPlan`.
- **Entity** (object): identity BY ID + a lifecycle; its operations are an OPERATION MODEL — a
  behavioral repertoire that PRODUCES or ACTS on collaborators, not a self-projection. `HealthSystem`
  (admits, employs), `Generalist` (consults, coordinates), `Remediator` (administers).

**Clinicians are entities BY CONSTRUCTION.** Statelessness is NOT the test — a stateless
`NetworkSpecialist` is still an entity, because `Clinician` carries a `clinicianId()` (identity by
id) AND each species implements its own operation model. The genus/species, made into the SPI:

| type | operation model |
|---|---|
| `Clinician` (genus) | `clinicianId()` — identity, the one universal |
| `Specialist` (diagnostician) | **assess** (always) + **prescribe** (conditional — a reply ALWAYS carries an Assessment, a Prescription only when there is a treatment; a decline is "assessed, did not prescribe") |
| `Remediator` | **administer** (a Prescription → outcome) — neither diagnoses nor prescribes; that is WHY "a clinician but not a doctor" |

**Per-role PORT-EXPOSURE GRADIENT (decided — not a blanket "clinicians are port services").** Who
crosses the seam is decided per role by who needs to call it:
- `HealthSystem` (+ the `DoctorConsultingService` it returns) — crosses NOW; the one door the boot
  pipeline uses (`awaitService(HealthSystem).admit(patient)`).
- diagnosticians (`Specialist`s) — stay OSGi-INTERNAL forever; the doctor's private roster, collected
  by DS `@Reference`; a pipeline no more names `NetworkSpecialist` than a patient names a radiologist.
- `Remediator` — surfaces on the port LATER, when the boot pipeline itself becomes OSGi orchestration
  ([[pipeline-orchestration-osgi-vision]]): administering is an orchestration STAGE action, driven on
  the operator's terms (loop-closure between visits, [[doctor-remediation-model]]).

**Two findings the detector surfaced (the "we may discover some missing / mis-folded" the user
predicted):**
1. `DriftSpecialist` has an operation model (`review`, and it WRITES to the ledger via
   InterventionLedgerWriter — a real effect) but is NOT a `Clinician` today → a MISSING entity, held
   beside the roster. Candidate to enroll as a clinician (a third species — an efficacy reviewer —
   neither diagnostician nor remediator).
2. `Specialist.diagnose(Referral) → ReferralReply` FUSES assess+prescribe into one method; the
   operation model is real but implicit. Open: leave fused or surface as two verbs.

See [[multiplexor-two-models-design]] [[doctor-live-record-roadmap]] [[object-graph-navigability-principle]].
