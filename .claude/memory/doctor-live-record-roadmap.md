---
name: doctor-live-record-roadmap
description: "NEXT WORK (fresh session, 2026-06-09 agreed). 3-step chain off main: (1) wire MedicalRecord into the live doctor [foundation], (2) remediation model, (3) what-if planner. Step 1 first because step 2 hard-requires it. Start with a brainstorm + design doc, then code. New branch off clean main (query API shipped b3e01bfd)."
metadata:
  node_type: memory
  type: project
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

The medical-record query API is SHIPPED to main ([[medical-record-impl-complete]], squash `b3e01bfd`,
pushed to origin/main; old `feature/medical-record-accumulator` kept LOCAL-ONLY as history archive).
Shipping it UNBLOCKED the next chantier and changed what's possible. This memory is the agreed
roadmap to resume from in a FRESH session — the user explicitly stopped here to start clean.

**THE AGREED 3-STEP CHAIN (user-confirmed dependency order, 2026-06-09):**

1. **Wire MedicalRecord into the LIVE doctor** — the foundation, do FIRST. Smallest step; it cashes in
   the merge and is the hard prerequisite for steps 2 & 3.
2. **Remediation model** ([[doctor-remediation-model]]) — Referral→ReferralReply round-trip, Remediator
   tier (nurse/pharmacist/physiotherapist), recruit-a-specialist. Mature DESIGN already in
   `docs/architecture/doctor/runbook-doctor.adoc`.
3. **What-if planner** ([[preview-whatif-topic]]) — counterfactual replay over `dependsOn`; its inner
   loop LOOPS over the step-2 round-trips and CONSUMES the record. Real first move there = DAG-as-data
   (topology as data, not the hard-coded stage order), NOT placement (placement is gone).

**WHY THIS ORDER (the user's own reasoning, validated against code):**
- The CONCRETE GAP (grep-verified 2026-06-09): `Generalist.consult(Symptom, Dossier)` takes **NO
  record**. `historyOf`/`efficacyOf` exist + are tested, but the ONLY live caller of
  `MedicalRecordReader` is the OFFLINE `MedicalRecordDump`. The in-run doctor is still MEMORYLESS — it
  cannot yet ask "seen this symptom before? did the last Rx work?". This was deliberately parked: see
  [[task14-readonly-preview-integration]] "option a — in-run reader, ctx.dryRun guard — deferred".
- Step 2 HARD-REQUIRES step 1: a `Referral` carries *references (not copies) to the longitudinal
  patient record AND sibling prescriptions* ([[doctor-remediation-model]] pt.4). No live record ⇒ no
  Referral. Prerequisite, not mere benefit.
- Step 3 sits last: [[preview-whatif-topic]] calls the patient record ESSENTIAL and its planner inner
  loop "loops on these round-trips" — so it needs BOTH step 1 (record) and step 2 (round-trip).

**STEP 1 — OPEN DESIGN QUESTIONS to brainstorm BEFORE coding (do NOT guess the seam):**
- Param vs held: does `consult` TAKE a `MedicalRecord` (aligns with T1 "reactive consult, record
  accessed as a param / edge-propagated" — [[runbook-doctor-state]]), or does the Generalist HOLD one
  (instance-passing)? T2 in runbook-doctor-state is exactly this open tension (stateless vs panel).
- WHERE the in-run read happens: the doctor runs INSIDE the Pulumi program; the record is reconstructed
  lock-free in-process (PROVEN by the abandoned sandbox). Need the ctx.dryRun guard story from
  [[task14-readonly-preview-integration]] (the in-run reader was option a there).
- The query API surface is concrete now: `MedicalRecordReader.read(Patient) → MedicalRecord` (patient
  bound once), fed by `SnapshotSource` whose Pulumi impl is `StackHandleSnapshotSource` over
  `StackHandle` (module `pulumi-automation-ext`). Design at
  `docs/architecture/doctor/medical-record-query-api-design.adoc`.

**PROCESS for the fresh session (the pattern that's served us):**
- New branch off the now-clean `main` (e.g. `feature/doctor-live-record`). main has EVERYTHING.
- BRAINSTORM the wiring seam first → land a design in `docs/architecture/doctor/` (prose + C4/UML
  mermaid, [[docs-diagrams-not-java]]) → THEN subagent-driven execution. [[works-best-from-concrete-code]]
  + design-before-code.
- Conventions in force: [[sequential-no-compat-workflow]] (no compat, delete old paths same change),
  [[error-handling-layered-contract]] (Optional vs typed checked exception), [[build-verification-gotchas]]
  (count surefire, never trust BUILD SUCCESS; `flox activate -- ./mvnw ... -Dmaven.build.cache.skipCache=true
  -DskipTests=false`), [[wip-guard-hooks]] (wip/ never reaches main; migrate docs before merge),
  [[working-style-narrate-progress]] (narrate intent before each tool batch — user is anxious in silence).

**NON-BLOCKING carry-overs (documented, not gating):** deferred backlog #4 `StackCheckpoint.snapshot`
catch-all narrowing + #5 efficacy first-Rx provisional ([[efficacy-first-prescription-provisional]]);
2 pre-existing Dependabot vulns flagged on the repo at push (1 critical/1 moderate, NOT from this
branch); post-hoc RE-RENDERING of the runbook .adoc still Design-Only (distinct from the record
reconstruction we shipped). Adjacent unrelated chantiers if the user pivots: [[seed-vcluster]],
[[config-restructuring-state]] Increment 2 (the doctor's first declared use case — also needs step 1).
