---
name: world-gateway-2d-execution-state
description: World-gateway 2D IN EXECUTION (feature/cluster-edge, subagent-driven). Zone-0 DONE (networknt pinned, gateway-document-codec built, SCHEMA_CONCORD gate wired). BUT a code-structure carto INVALIDATED the ASM concord approach — PIVOT (user-chosen): records-as-contract per Document, JSON schema GENERATED from the record (not hand-written, not ASM-reconciled). NEXT = write the records-as-contract DESIGN before resuming execution. Plan: docs/superpowers/plans/2026-06-30-world-gateway-2d-schema-contract.md (zone-0 part now superseded).
metadata:
  type: project
---

## Where we are (2026-06-30, mid-2D, context compaction)

Executing the 2D plan subagent-driven. Commits on feature/cluster-edge after the jsr310 fix arc:
- `7e704838`+`33fc1944` Task 1 — networknt pinned in BOM + added to **maven-embed-staging-ext** (build-tooling only; NOT doctor-core — nothing imports it at runtime in 2D, validate is OFF until the capstone).
- `3183a79e`+`f71205b2`+`e690a72a` Task 2 — `gateway-document-codec`: ONE flat-jar module in osgi/foundation, shaded flat host-side + nested into doctor-core's Bundle-ClassPath (`-includeresource;lib:=true`). New pattern [[nesting-our-own-flat-module-per-realm]]. Package `io.nxmatic.rke2lab.world.gateway.codec`, sibling of the seam (NOT in it — seam shares one String copy; codec carries jackson, two realm copies).
- `7448daae`+`8d483652` Task 3 — `SCHEMA_CONCORD` gate: added to BOTH StagingGate enums (annotation + ASM mirror), `SchemaConcord` (meta-schema validity via networknt + concord) + `CoordinateFieldUsage` (ASM). Slug map DERIVED from `Coordinate.class` `<clinit>` bytecode (not hardcoded). Gate dormant (no schemas) → schema-concord 0/0, reactor green.
- `308da3ad` memory: the FIELD_* elimination backlog.

## THE PIVOT (user decision — do NOT revert to ASM concord)

A 6-coordinate structure carto (Explore agent, this session) proved the ASM part-(b) concord engine
is a DEAD END:
1. **4 translators** read-coord-A-write-coord-B in one method (DefaultReadinessAuthority,
   DefaultInterventionIntake, Generalist, +MedicalRecordReader) → class-granularity FIELD_* attribution
   over-assigns (e.g. readiness-verdict would get scenarioId+override which belong to the checkpoint).
2. **`intervention` uses NO FIELD_***  — its contract is already `Intervention.toOutputMap()` (the
   record IS the model). ASM finds nothing to attribute.
3. Documents **NEST** (observations[], consultationReport{}, expectations[], FIELD_CONSULTATION_REPORT[])
   — a flat FIELD_* set cannot describe them.

User's call (both questions answered): **monter d'un niveau — record-per-Document is the contract, the
JSON schema is GENERATED from the record** (intervention already does this; generalize to all 6). AND
**automate it generically** — scan the records, generate schemas, no hand-written schema, no FIELD_*.
This FUSES the [[eliminate-field-constants-via-schema-binding-backlog]] INTO 2D.

Consequence: Task 3's `CoordinateFieldUsage` (read/write ASM concord) becomes OBSOLETE. The gate's
MEANING changes: not FIELD_*↔properties, but "every Document of coordinate X is built/read via X's
record, never via parallel put(String,…)". Tasks 4-9 become "define 6 records + migrate
producers/consumers onto them", not "write 6 schemas".

## THE CARTO (authoritative — do NOT re-scan; producer WRITES are the true shape)

- **readiness-checkpoint** (host produces: SystemdAdapterStage, ClusterReadinessStage) — writes
  scenarioId, recordedAt, observations[] (each obs: status, summary, symptom, …details). Consumed by
  Generalist.consult/observationsFrom, DefaultReadinessAuthority.assess (reads scenarioId/failed/override).
- **readiness-verdict** (DefaultReadinessAuthority.assess; DefaultInterventionIntake.error) — writes
  action, reason. Host reads action. FLAT.
- **consultation** (Generalist.consult) — writes scenarioId, narration, diagnosisAdoc, + nested
  consultationReport{checkpointId,observations[],plan{}} + expectations[]. Host reads narration.
- **intervention-request** (host RecordInterventionCommand) — writes problem, what, provenance?,
  prescriptionRef?, when. Consumed by DefaultInterventionIntake.canonicalize. FLAT.
- **intervention** (InterventionDocuments.of → Intervention.toOutputMap) — provenance, when, what,
  problem, prescriptionRef? (+open `details`). NO FIELD_* — the record is the contract already.
- **visit** (host StackMedicalRecordJournal.visitDocument) — writes version, when,
  consultationReport[] (blobs), expectations[] (list-of-lists). Consumed by MedicalRecordReader.read.

## NEXT (resume here)

1. WRITE the records-as-contract design (the user said OK to plan-mode). Decisions to settle in it:
   (a) WHERE the records live — likely the `world-gateway` seam (String-only, NO jackson): a record of
   scalars+lists+nested records can live there; its serialization (record↔String) belongs to the
   per-realm `DocumentCodec` (jackson). Confirm the seam stays jackson-free.
   (b) schema generation build-time vs runtime — likely build-time projection from the records (jackson
   has jackson-module-jsonSchema, or generate from RecordComponents).
   (c) nesting via nested records (the strong argument for records over a flat descriptor).
   (d) the gate's NEW invariant (no parallel put(String,…) construction; Document built only via a record).
   (e) how this respects realm isolation (records cross the seam as data? or only String crosses and the
   record is realm-local on each side? — 2C says only String crosses; the record is likely a per-realm
   shape the codec maps to/from String, NOT a type crossing the seam — VERIFY against 2C invariant).
2. Get user GO on the design, THEN re-plan Tasks 4-9 and execute.

Build command (user-confirmed, [[maven-build-cache-and-staging-verify]]): `flox activate -- ./mvnw
package -Pall-worlds -Dmaven.build.cache.skipCache=true -DskipTests=false` (SKIP cache, not disable;
no clean unless stale). Extension changes need the two-phase dance ([[osgi-staging-extension-chantier]]).
See [[world-gateway-2c-complete-2d-designed-state]] [[realm-library-isolation-state]].
