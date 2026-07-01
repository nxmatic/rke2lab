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

## DESIGN WRITTEN + USER GO (2026-06-30) — now EXECUTING

Design doc: `docs/superpowers/plans/2026-06-30-world-gateway-2d-records-as-contract-design.md`.
User said GO. The 5 decisions are SETTLED there; the 3 open points are resolved (user accepted the leanings):
- (a) wire-records live in the **`world-gateway` seam** (jackson-free, system-exported, one shared
  class — same status as Document/Coordinate; the TYPE never crosses, only the String, via the
  per-realm DocumentCodec). Confirmed against 2C: stricter than today, does not reopen the JsonNode leak.
- (b) schema GENERATED build-time from `RecordComponents`, generator in `maven-embed-staging-ext`.
- (c) nesting via nested wire-records (Consultation→ConsultationReportWire+List<ExpectationWire>).
- (d) gate invariant REWRITTEN: "every Document of coordinate X built/read via X's wire-record, never
  parallel put(String,…)". Old `CoordinateFieldUsage` ASM read/write engine = DELETED (obsolete).
- (e) isolation respected + strengthened.
- Open-1 PACKAGE: flat in `world.gateway.port` (not a `.wire` sub-pkg).
- Open-2 WIRE vs RICH: option (ii) — wire-record is schema-source AND serialization unit; rich
  `doctor-records` types stay the domain model and gain `toWire()` where they're the producer.
- Open-3 OPAQUE blobs (visit, host intervention pass-through): `Map<String,Object>`/`List<Object>`,
  generator emits `{}`.

### Re-planned tasks (replace old 4-9), gate stays WARN until T10 flip:
- T4 scaffolding: wire-record base + opaque pass-through type, schema generator in extension, gate
  new invariant WARN, 0 schemas yet (0/0 green). Subsumes old Task 3 rewrite.
- T5 readiness-verdict (flat, OSGi-only) — proves the loop end-to-end.
- T6 intervention-request (flat, HOST producer) — first host migration via shared seam record.
- T7 intervention (OSGi producer already record-based; host pass-through opaque).
- T8 readiness-checkpoint (host producers, nested observations[]).
- T9 consultation + visit (nesting + opaque blobs).
- T10 delete WorldGatewayCatalog FIELD_* block; FLIP SCHEMA_CONCORD WARN→ERROR; full reactor green = lock.

EXECUTION POSTURE: T4 done inline (design-sensitive, full context). T5-T9 subagent-driven (repetitive
per-coordinate migration). Build: `flox activate -- ./mvnw package -Pall-worlds
-Dmaven.build.cache.skipCache=true -DskipTests=false`; extension = two-phase dance.

## T4 + T5 DONE (2026-07-01)

- **T4** (commit `63622488`): `@DocumentContract(Coordinate)` seam annotation, `RecordSchemaProjector`
  (RecordComponents→JSON schema by ASM), `DocumentContractScan` (replaced CoordinateFieldUsage),
  `SchemaConcord` rewritten (every Coordinate must have a wire-record whose projected schema is
  meta-schema-valid; missing = WARN worklist). Gate WARN, 6-coordinate worklist, reactor green.
- **T5** (readiness-verdict, first coordinate): `ReadinessVerdict(Action, reason)` wire-record —
  holds the Action enum TYPED (the FIELD_* typing goal). `WireEnum` seam marker (Action implements
  it) + `WireEnumModule` (jackson glue, codec `.internal`, non-exported) maps any WireEnum↔slug
  generically. `DocumentCodec` gained typed `encode(record)`/`decode(String,Class)`. All 6
  producers/consumers migrated (DefaultReadinessAuthority, DefaultInterventionIntake.error host
  SystemdAdapterStage + RecordInterventionCommand + 5 tests); `FIELD_ACTION`/`FIELD_REASON` DELETED.
  SCHEMA_CONCORD now 5 warn. NOT yet committed as of this note.

### THE BIG T5 DESIGN SHIFT — codec is now a `type=library` bundle (user-driven)
The codec was a flat-jar nested-private in doctor-core. The user pushed to resolve the
"doctor-core owns a foundation concern" tension NOW: promoted it to an **autonomous dual-realm
bundle** via a NEW staging category `embed; type=library` (jackson's own treatment, for our code):
- `EmbedCapability.TYPE_LIBRARY` + `isLibrary()`; `INSTALL_FILTER` includes it (documentary).
- `StagingClosure.isRealmLibrary` returns `b.embed().isLibrary()` for ours → staged bundle AND kept
  flat (in realmLibraryGas, so NOT shade-excluded). The ONLY embed type in both realms.
- codec bnd: BSN `io.nxmatic.rke2lab.gateway.document.codec`, Export `world.gateway.codec`, Import
  jackson+seam, `Provide-Capability embed; type=library`. doctor-core IMPORTS it (no more nesting).
- Verified DUAL in the uber-jar: `META-INF/bundles/gateway-document-codec.jar` AND flat
  `world/gateway/codec/DocumentCodec.class`. Pattern memory rewritten: type=library is now PREFERRED
  over nesting ([[nesting-our-own-flat-module-per-realm]] [[codec-foundation-single-exporter-when-needed-backlog]]).
- Naming settled: KEEP `gateway-document-codec` (role, not jackson mechanism); it is NOT a jackson
  fragment (depends on seam, dual-realm, owns its export) — only `WireEnumModule` is a jackson extension.
- Dep-scope/version centralization DEFERRED by user ([[centralize-seam-dep-scope-version-backlog]]).

Build command (user-confirmed, [[maven-build-cache-and-staging-verify]]): `flox activate -- ./mvnw
package -Pall-worlds -Dmaven.build.cache.skipCache=true -DskipTests=false` (SKIP cache, not disable;
no clean unless stale). Extension changes need the two-phase dance ([[osgi-staging-extension-chantier]]).
See [[world-gateway-2c-complete-2d-designed-state]] [[realm-library-isolation-state]].
