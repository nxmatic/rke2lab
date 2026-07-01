# World-gateway 2D — records-as-contract (DESIGN, awaiting GO)

**Branch:** `feature/cluster-edge`. **Status:** design only — no execution until user GO.
**Supersedes:** the ASM-concord half of `2026-06-30-world-gateway-2d-schema-contract.md` (zone-0
of that plan stays: networknt pinned, `gateway-document-codec` built, `SCHEMA_CONCORD` enum wired).
**Pivot source:** `world-gateway-2d-execution-state.md` (the carto + the user's "monter d'un niveau").

## 1. The problem the pivot fixes

The original 2D gate (`SCHEMA_CONCORD` part-b) tried to reconcile, by ASM, the `FIELD_*` a
coordinate's code reads/writes against a hand-written `<slug>.schema.json`. The 6-coordinate carto
killed it: 4 translators read-coord-A-write-coord-B (class-granularity over-attributes), `intervention`
has no `FIELD_*` at all (its record `toOutputMap()` already *is* the contract), and documents nest.

**The fix (user decision):** the contract is a **record per coordinate**; the JSON schema is
**generated from the record** by scanning its `RecordComponents`. No hand-written schema, no `FIELD_*`
catalog. This fuses the `eliminate-field-constants-via-schema-binding-backlog` *into* 2D.

## 2. What the code already gives us (verified this session)

- **`doctor-records` is already records-as-contract**: every record carries a flat `toOutputMap()`,
  no jackson, built from JDK scalars + lists + nested records + seam enums. `Intervention` is not
  special — it's the module's rule.
- **But `doctor-records` is OSGi-only**: 7 OSGi modules depend on it; **zero** host imports. And
  **3 of 6 coordinates are produced host-side** (`readiness-checkpoint`, `intervention-request`,
  `visit`) where there is no record — they hand-roll `ObjectNode`/`LinkedHashMap` with `FIELD_*`.
- **The seam `world-gateway` is jackson-free, system-exported (one shared copy), `-noimportjava`**,
  and `doctor-records` *already* depends on it. `Document` + `Coordinate` already live there.

**Conclusion that sets the topology:** the contract cannot be the *rich* domain record
(`Intervention(Provenance, Optional<RemediationProgramRef>, …)`) — a generic scanner projects
`RecordComponents` to a schema and must describe the **flat wire shape** (`"provenance": "<id>"`),
not the `Provenance` object. So the contract is a **flat wire-record per coordinate**, and because
the host produces 3 of them, those wire-records **live in the `world-gateway` seam** (decision (a),
user-confirmed).

## 3. The five design decisions (settled)

### (a) WHERE the wire-records live — **the `world-gateway` seam** ✅ user-chosen

New package `io.nxmatic.rke2lab.world.gateway.port` (or a `.wire` sub-package — see §6 open point),
one record per coordinate, all jackson-free, scalars + `List` + nested records + seam enums only:

```
world.gateway.port/
  Document.java            (envelope — exists)
  Coordinate.java          (exists; each constant gains a link to its wire-record type)
  ReadinessCheckpoint.java (scenarioId, recordedAt, failed?, override?, observations[])
  ReadinessVerdict.java    (action, reason)            ← flat
  Consultation.java        (scenarioId, narration, diagnosisAdoc, consultationReport{}, expectations[])
  InterventionRequest.java (problem, what, provenance?, prescriptionRef?, when)  ← flat
  InterventionWire.java    (provenance, when, what, problem, prescriptionRef?, + open details)
  Visit.java               (version, when, consultationReport[blob], expectations[blob])
```

The seam stays pure: a wire-record of JDK scalars + lists + nested wire-records references nothing a
bundle owns; it is **data**, not a jackson type. **The type never crosses the boundary** — only the
`String` payload does, exactly as `Document` does today (§(e)). Each realm holds its own copy of the
wire-record class (system-exported, one class) and maps it ↔ `String` with ITS jackson, in the
per-realm `DocumentCodec`.

### (b) Schema generation — **build-time, from `RecordComponents`** ✅

A build-time generator walks each wire-record's `RecordComponents` and emits
`<slug>.schema.json`. Scalars → `string`/`integer`/`boolean`; `Optional<X>` → optional property;
`List<X>` → `array`; nested wire-record → nested `object` (recurse); seam enum → `enum` of its
slugs. This is the "automate it from the classes you scan" the user asked for. The generated schema
is an artifact the gate checks and the capstone's runtime validator later loads — it is **not**
hand-edited.

Where the generator runs: inside `maven-embed-staging-ext` (it already does ASM scanning and owns
the gate), reusing networknt only to self-validate the emitted schema against the meta-schema. **No
runtime codegen** — the wire-records are hand-written once (they ARE the source); only the schema is
generated.

### (c) Nesting — **via nested wire-records** ✅ (the decisive argument for records over a flat descriptor)

`Consultation` holds a `ConsultationReportWire` and a `List<ExpectationWire>`; `ReadinessCheckpoint`
holds a `List<ObservationWire>`. The generator recurses, so the schema nests. This is exactly what a
flat `FIELD_*` set could not express (carto point 3).

**Opaque pass-through fields** (`visit`, host-side `intervention`): the host copies OSGi-written
sub-trees verbatim (`snapshot.outputsNamed(consultationReport)` is a blob it does not re-describe).
The wire-record models these as an opaque node type (a `JsonNode`-free placeholder — likely a
`Map<String,Object>` / `List<Object>` pass-through), and the generator emits `{}` (any-object) for
them. The contract for those is "this slot exists and is an object/array", not its inner shape —
which is correct: the inner shape is `consultationReport`'s own contract, checked where it's produced.

### (d) The gate's NEW invariant — **construction-by-record-only** ✅

`SCHEMA_CONCORD` stops meaning "FIELD_* ↔ properties". It becomes:

> Every `new Document(domain, X.slug(), payload)` for coordinate X has its `payload` produced by
> serializing X's wire-record (via a codec), and every read of an X document deserializes to X's
> wire-record. No parallel `put(String,…)` / `path(String)` construction of an X payload exists.

Mechanically (ASM, the gate's existing muscle): flag any call site that builds an `ObjectNode` /
`Map` payload for a `Document` of coordinate X **without** going through the wire-record. Concretely:
after migration, the `WorldGatewayCatalog.FIELD_*` constants are **deleted**; the gate's real
enforcement becomes "no code references a deleted constant" + "the schema generated from X's record
validates against the meta-schema" + "every coordinate has a wire-record". The old
`CoordinateFieldUsage` ASM engine (read/write attribution) is **deleted** — obsolete under this model.

### (e) Realm isolation (2C invariant) — **respected; verified against 2C** ✅

2C invariant: only the `String` payload crosses the seam; no jackson type, no bundle-owned type
crosses. Under this design:
- The wire-record is a **seam type** (system-exported, one shared class, jackson-free) — same status
  as `Document`/`Coordinate`/`Checkpoint`/`Patient` which already cross as identities. It is *data*,
  not a jackson type, so it does not reopen the `JsonNode`-leak that 2C closed.
- The record↔`String` serialization stays **per-realm** in `DocumentCodec` (host's flat jackson; the
  bundle's own jackson). No mapper crosses.
- So: the wire-record is a shared shape; the `String` is what physically crosses; the codec is
  per-realm. This is **stricter** than today (today the host hand-rolls `ObjectNode` independently of
  any shared shape), so it strengthens 2C rather than weakening it.

## 4. How the codec changes

`DocumentCodec` (already per-realm, `gateway-document-codec`) grows typed encode/decode keyed by the
wire-record, replacing the raw `JsonNode` encode/decode the producers call today:

```java
<T> String encode(T wireRecord);          // record → String, this realm's jackson
<T> T decode(String payload, Class<T> type); // String → record, this realm's jackson
```

`validate(payload, slug)` stays as-is (off in embedded, on at the capstone). The producers stop
touching `ObjectMapper`/`ObjectNode` directly and stop referencing `FIELD_*`; they build a
wire-record and call `codec.encode(record)`. Consumers call `codec.decode(payload, X.class)` and read
typed accessors.

## 5. Re-planned tasks (replaces old Tasks 4-9)

Pipeline-friendly, one coordinate at a time, gate stays WARN until the final flip:

- **T4** — Seam scaffolding: the wire-record base (opaque pass-through type), the schema generator in
  `maven-embed-staging-ext`, and the gate's new invariant wired WARN. Generator emits 0 schemas yet
  (no wire-records) → still 0/0, reactor green. *(This subsumes the old Task 3 rewrite.)*
- **T5** — `readiness-verdict` (flat, OSGi-only producer `DefaultReadinessAuthority` + the
  `DefaultInterventionIntake.error` twin; host reads `action`). Smallest, proves the loop end-to-end:
  record in seam → generator emits schema → codec round-trips → producers/consumers migrated →
  `FIELD_ACTION`/`FIELD_REASON` deleted.
- **T6** — `intervention-request` (flat, host producer `RecordInterventionCommand`; OSGi consumer
  `DefaultInterventionIntake.canonicalize`). First HOST migration — proves the host builds via the
  shared seam record.
- **T7** — `intervention` (OSGi producer `InterventionDocuments.of` already record-based — mostly
  re-point to the wire-record; host pass-through in `StackInterventionJournal` uses the opaque slot).
- **T8** — `readiness-checkpoint` (host producers `SystemdAdapterStage`, `ClusterReadinessStage`;
  nested `observations[]`; OSGi consumers `Generalist.consult`, `DefaultReadinessAuthority.assess`).
- **T9** — `consultation` + `visit` (the two nesting/pass-through cases:
  `consultationReport{}` + `expectations[]`; visit's opaque blobs).
- **T10** — Delete the now-empty `WorldGatewayCatalog` `FIELD_*` block; **flip `SCHEMA_CONCORD`
  WARN→ERROR**; full-reactor green at ERROR = the contract lock.

Each task: build `flox activate -- ./mvnw package -Pall-worlds -Dmaven.build.cache.skipCache=true
-DskipTests=false`; extension changes need the two-phase dance.

## 6. Open points to confirm at GO

1. **Package**: wire-records directly in `world.gateway.port` (flat, beside `Document`) vs a
   `world.gateway.port.wire` sub-package (export both). Leaning flat — fewer exports, the seam is
   small. *Your call.*
2. **Wire-record ↔ rich record**: OSGi has rich `doctor-records` (`Intervention`,
   `ConsultationReport`…). Two options: (i) the wire-record is the *only* shape and the rich records
   gain a `fromWire/toWire`; (ii) keep `toOutputMap()` and make the wire-record a thin projection the
   generator scans. Leaning (ii) — least churn in `doctor-records`, the wire-record is the
   schema-source while the rich record stays the domain model. *Confirm at GO; affects T7/T8/T9 size.*
3. **Opaque pass-through representation**: `Map<String,Object>`/`List<Object>` vs a named
   `OpaqueSubtree` seam type. Leaning the former — no new type, generator emits `{}`.

## 7. NOT in scope (unchanged)

Remote-validation capstone (turns runtime validation ON) is a separate increment after 2D. The
pre-existing seed-master BDD test-compile breakage is a separate chantier (will block the
full-reactor-with-tests gate at branch-finish — flagged, not ours).
