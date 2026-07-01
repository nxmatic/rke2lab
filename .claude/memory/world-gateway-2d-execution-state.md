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

## T6 DONE (2026-07-01) — intervention-request + codec modules matured

- `InterventionRequest(problem, what, Optional<String> provenance, Optional<String> prescriptionRef,
  Instant when)` wire-record, `@DocumentContract(INTERVENTION_REQUEST)`. Compact ctor normalizes a
  null Optional → empty (the codebase's Optional idiom, guards direct construction). Producer host
  `RecordInterventionCommand` (its `Args` record also refactored to Optional per user), consumer OSGi
  `DefaultInterventionIntake.canonicalize` (decodes to the record, keeps the graceful error-verdict
  flow), + 2 tests migrated. `FIELD_PROBLEM/WHAT/PROVENANCE/PRESCRIPTION_REF` deleted; `FIELD_WHEN`
  KEPT (visit/T9 still uses it). SCHEMA_CONCORD now 4 warn.
- Codec gained `InstantModule` (Instant↔ISO-8601, home-made, 2 lines) + jackson's own **`Jdk8Module`**
  for Optional. KEY LESSON (user-driven): a home-made OptionalModule was a dead end (fought jackson's
  ReferenceType/REQUIRE_HANDLERS internals) — jackson's Module system is the right tool; registered
  EXPLICITLY (`registerModule`), never `findAndRegisterModules`, so no ServiceLoader regression.
- jdk8 is NOT nested (unlike jsr310 in manifests-cdk8s): nesting is needed ONLY when a ServiceLoader
  discovers the module off a classloader (jsii's findAndRegisterModules drives jsr310). We register
  explicitly, so jdk8 is imported bundle-to-bundle like databind. Declared ONCE in the codec pom (the
  owner), NOT duplicated in seed-master.
- STAGING FIX (extension, two-phase): `type=library` now feeds `indexHostFlatPackages` +
  `seedRealmLibraries` (was `isDomain()` only). Without it, a library's imported jackson jars (jdk8)
  were staged but NOT kept flat → host copy missing. Now jdk8 is dual (staged bundle + 23 flat
  classes), exactly like databind. General fix for any future dual-lib.
- New backlogs graved: [[sweep-objectmapper-onto-codec-backlog]] (remove residual `new ObjectMapper()`
  after T9), [[jspecify-nullmarked-default-backlog]] (@NullMarked package default, post-2D).
- NOT yet committed as of this note.

## T7 IN PROGRESS (2026-07-01) — intervention, NOT built, NOT committed

BIG MODEL FIX (user-confirmed "c'est un bug"): the `intervention` coordinate had TWO wire shapes
under one slug — a bug (1 coordinate MUST = 1 schema). Root cause: the host read path wrapped
`{interventions:[blob]}`, but that `[...]` was Pulumi's `outputsNamed`→List transport framing LEAKING
into the payload (InterventionResource registers ONE intervention via `Output.of(data)`; outputsNamed
collects it as a 1-element list). The write path already wrote ONE flat intervention. TRUE contract =
ONE intervention. Fix = UNWRAP the Pulumi framing at the host, one Document per blob.

Changes DONE (files edited, not yet built):
- NEW `InterventionWire(provenance, when:Instant, what, problem, Optional<String> prescriptionRef,
  Map<String,Object> details)` in world-gateway seam, `@DocumentContract(INTERVENTION)`. Refs stay
  RAW strings (doctor vocab parsed OSGi-side). `details` is now an EXPLICIT nested Map field (was
  putAll-flattened at root by Intervention.toOutputMap).
- `InterventionDocuments.of` (OSGi producer): builds InterventionWire from Intervention (provenance.id,
  problem.toRef, prescriptionRef.map(id)) + `codec.encode`. No more toOutputMap.
- `InterventionReader`: `fromOutputMap(Object)` → `fromWire(InterventionWire)` — TYPED, no more Object
  + instanceof guards (user praised this: "on laisse pas passer n'importe quoi"). Keeps tolerance
  (unparseable required ref → empty).
- `InterventionLedgerReader`: decodes ONE InterventionWire per Document (was payload→FIELD_INTERVENTIONS
  list→blobs→fromOutputMap). Uses codec.
- `StackInterventionJournal` (host): `interventionDocuments()` emits one `intervention` Document PER
  blob from `outputsNamed` (was one Document wrapping the list). serialize(Object) now.
- `InterventionLedgerLayout.OUTPUT_KEY`: decoupled from WorldGatewayCatalog → literal "interventions"
  (a host-internal Pulumi transport key, NOT a seam wire field). Import of catalog dropped.
- `WorldGatewayCatalog.FIELD_INTERVENTIONS` DELETED.

STILL TODO for T7 (resume here):
1. Add `InterventionWire` to docs/architecture/osgi/world-gateway-spec.adoc (SPEC_COVERAGE names it,
   like ReadinessVerdict/InterventionRequest — else ERROR).
2. Migrate tests: `InterventionReaderTest` (fromOutputMap→fromWire, build InterventionWire not Map),
   `InterventionLedgerReaderTest` (rewrite: one InterventionWire per Document, drop FIELD_INTERVENTIONS
   list shape — uses it at lines ~110/123/178), any others referencing the old shapes. Grep
   FIELD_INTERVENTIONS + `.fromOutputMap` under intervention tests.
3. Build: PLAIN reactor (no extension change in T7): `flox activate -- ./mvnw package -Pall-worlds
   -Dmaven.build.cache.skipCache=true -DskipTests=false`. Expect SCHEMA_CONCORD 3 warn (readiness-
   checkpoint, consultation, visit). Commit.

## T7 DONE (2026-07-01) — all done, reactor + tests GREEN, SCHEMA_CONCORD 3 warn

All 3 gestes done. Spec names InterventionWire (+ fixed a stale mention: the para said the deleted
home-made OptionalModule; now says jackson's Jdk8Module). Tests migrated:
- `InterventionReaderTest`: fromOutputMap(Object/Map)→fromWire(InterventionWire); dropped the
  null/non-map/unparseable-when cases (the TYPE eliminates them — the hardening).
- `InterventionLedgerReaderTest`: rewritten off the `{interventions:[...]}` envelope (deleted) — now
  one InterventionWire per Document; malformed = undecodable payload or unparseable wire.
- `RecordInterventionCommandTest`: fakeIntake builds InterventionWire; and (user push) the test's
  residual `new ObjectMapper()` REMOVED — `wireOf(doc)` decodes via CODEC, assertions on the
  contract (wire.provenance()/when()/prescriptionRef()) not on string map keys. Tests-at-the-contract.
- Fix: RemediationProgramRef wire id is "restart-systemd-unit" (not "restart-unit").
User praised: typed door not open door; test at contract not implementation. NOT yet committed.

## SCOPE DECISION (user 2026-07-01): ALL reliability work stays IN THIS BRANCH

The user wants the whole reliability arc (design+build) DONE on feature/cluster-edge, NOT deferred
post-merge ("je me connais" — a deferred backlog won't happen). So after the 2D coordinate arc
(T7 done → T8, T9, T10 flip), do these IN THIS BRANCH, each its own commit:
- 2E: harden internal fromOutputMap(Object)→typed (doctor-records internal, non-wire) —
  [[harden-internal-fromoutputmap-backlog]]. User wants it right after the wire arc, not last.
- 2F: sweep residual `new ObjectMapper()` onto the codec — [[sweep-objectmapper-onto-codec-backlog]].
- 2G: JSpecify @NullMarked package default (non-null by default) —
  [[jspecify-nullmarked-default-backlog]] (will delete many now-dead null guards — synergy with typing).
- 2H: centralize seam dep scope/version in dependencyManagement/BOM —
  [[centralize-seam-dep-scope-version-backlog]].
(Naming caveat: 2E-2H are shorthand; @NullMarked + dep-centralization are repo-transverse, not strictly
world-gateway. Order TBD with user; capstone remote-validation still after.)

## fromOutputMap hardening scope (user Q, 2026-07-01)

`fromOutputMap`→`fromWire` (Object→typed) widens ONLY where it's gateway WIRE:
- `ConsultationReportReader`/`ExpectationReader` (consultation coordinate) → hardened in T9 via nested
  ConsultationReportWire/ExpectationWire sub-records.
- `ExpectationPredicate.fromOutputMap`/`ResolutionPredicate` = doctor-records-INTERNAL record↔map
  (kind-discriminator dispatch, never crosses the seam) → separate post-2D backlog, NOT 2D.

Build command (user-confirmed, [[maven-build-cache-and-staging-verify]]): `flox activate -- ./mvnw
package -Pall-worlds -Dmaven.build.cache.skipCache=true -DskipTests=false` (SKIP cache, not disable;
no clean unless stale). Extension changes need the two-phase dance ([[osgi-staging-extension-chantier]]).
See [[world-gateway-2c-complete-2d-designed-state]] [[realm-library-isolation-state]].
