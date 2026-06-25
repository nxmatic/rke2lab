---
name: multiplexor-two-models-design
description: "DESIGN settled 2026-06-24 (brainstorm, NOT yet built) — the host↔OSGi data exchange is reframed as TWO distinct world-models (OSGi diagnostic model vs host stack model) meeting ONLY at a Document contract, never sharing a type. A DomainDagMultiplexor (OSGi) mux/demuxes an envelope stream; a host DomainDagAdapter mirrors it to Pulumi. Mechanism = DS (chosen over fragment), so records stay private. Invariant: Pulumi is host-only vocabulary."
metadata:
  node_type: memory
  type: project
---

## The reframe (the load-bearing idea)

The seam between the host and OSGi worlds is bloated (doctor-port = 61 exported types; ~2000
lines of hand-serialization — `toOutputMap`/`OUTPUT_KEY`/`*Reader` — smeared across ~24 doctor
records) BECAUSE the records think in Pulumi: they serialize THEMSELVES to Pulumi outputs. The
fix is to stop treating it as ONE model crossing a boundary and see **TWO distinct world-models
that never share a type**, meeting only at a serialized **Document** contract:

- **OSGi world = the diagnostic model** — `MedicalRecord`, `ConsultationReport`, `Intervention`,
  `Observation`, `Specialist`, `Generalist`, `HealthSystem`. Speaks medical; **never** Pulumi.
- **host world = the stack model** — `StackHandle`, `StackSnapshot`, `StackHistory`,
  `StackCheckpoint`, `StackCoordinate`, `LiveMedicalRecordRegistry`. The ONLY Pulumi-aware world.

## The invariant the user stated

**Pulumi is a host-only word.** Verified: zero `com.pulumi.*` type refs in `osgi/` today (no CODE
leak), but the WORD "Pulumi" is smeared across ~24 doctor-port files in vocabulary — `Patient` is
defined as "a Pulumi stack", `StackCoordinate` as "a Pulumi stack id", `OUTPUT_KEY` as "the Pulumi
output key". That conceptual leak is the bug. Target guardrail: **`grep -ri pulumi osgi/` == 0**
(same spirit as the no-snapshot-install enforcer).

## The roles (each blind beyond its concern)

| role | world | owns | knows Pulumi? |
|---|---|---|---|
| domain (doctor) | OSGi | its DAG records + reasoning | no |
| **DomainDagMultiplexor** NEW | OSGi | envelope `(domain, coordinate)`; mux/demux a transport-neutral DocumentStream | no — a stream codec |
| **DomainDagAdapter** NEW | host | maps DocumentStream ↔ Pulumi outputs; live stack I/O | yes — the ONLY one |

Egress/ingress are MIRRORS meeting at the stream: domain DAG → DomainDagMultiplexor (stamp+mux) →
DocumentStream → DomainDagAdapter (export per `domain/coordinate`) → Pulumi; and the reverse.
Pulumi becomes ONE possible sink behind the adapter — the same stream could later land in a git
repo (the "clone the stack onto git, immutable" idea), DomainDagMultiplexor unchanged.

## Operator-legibility constraint (decides the wire shape)

The operator must read the stack with plain `pulumi stack output` (NO rke2lab tooling) and
understand it. ⇒ the **envelope rides in the output KEY** (`doctor/consultationReport`), the
**value is the bare DAG** (Pulumi-native structured JSON, diffable). NO `schema:`/`payload:`
wrapper in the operator's face; NO opaque multi-doc YAML blob (kills Pulumi diff — rejected).
Optional per-domain `domain/summary` shallow human line alongside the deep DAG.

## ★ GENERALIZED: DS-contribution is THE inter-domain relation pattern (2026-06-25)

The DomainDagMultiplexor mechanism is not special — it is one instance of the general inter-domain
relation pattern, and the user generalized it explicitly: **a contributing domain implements an SPI
interface, annotates the impl `@Component`, and the contributed domain receives it via
`@Reference(cardinality=MULTIPLE) List<T>`.** Two SPI families already share the exact mechanism:

| SPI interface | contributor | consumer | received via |
|---|---|---|---|
| `Specialist` (reasoning) | a domain (cluster) | doctor | `@Reference List<Specialist>` |
| `DomainDagMapper` (serialization) | each domain | the DomainDagMultiplexor | `@Reference List<DomainDagMapper>` |

Same shape: shared SPI package + `@Component` on the provider + `@Reference List` on the consumer;
neither imports the other's impl — they meet on the SPI. Proven by `FragmentContributedComponentTest`
(a `@Reference List` receives a contributed component). So the **4th package role `.spi`** (a
dedicated `doctor-spi` module, taxonomy sibling of `-port`) is where ALL inter-domain contracts live;
DS is the single contribution mechanism for inter-domain relations, whether reasoning or data.

NOTE on sequencing: the `@Component`/`@Reference List` WIRING is the target, NOT step 1b. Per the
earlier decision, DS annotations were stripped from `-core` ("re-add at the peer-to-peer moment").
Step 1b only creates the receptacle (`doctor-spi`); `Doctor.java` keeps its transitional static
`new ClusterSpecialist()` roster until the cluster increment re-adds DS.

★ SPECIALIST DISTRIBUTION PLAN (user: "chacun sa responsabilité — le domaine cluster définit le
ClusterSpecialist, donc c'est lui qui le contribue au doctor"). Correct in principle; reality forces
the sequencing (decided: finish package-isolation 1b-ii/1c FIRST, distribute as the NEXT increment):
- `DbusTcpSpecialist` → systemd domain (exists; already deps `systemd.port.SystemdUnitId`).
- `NetworkSpecialist` → netplan domain (exists; currently a stub).
- `ClusterSpecialist` → cluster domain — **does NOT exist yet**; distributing it = creating the
  cluster domain = the cluster-edge chantier. This is why it cannot move in step 1.
- `DriftSpecialist` → likely STAYS in doctor (self-drift auto-diagnosis; deps InterventionLedgerWriter).
Distribution requires real DS wiring (@Component on each + @Reference List<Specialist> in doctor) —
that is roadmap step 4 territory. The transitional `new XxxSpecialist()` in Doctor.java is explicitly
temporary, to be replaced when each specialist moves home with its @Component.

## ★ KEYSTONE DECISION: path-addressing — records NEVER cross to the host (2026-06-25)

The knot we had missed: `DoctorConsultingService` is a LIVE in-process call (`consult(Symptom,
Observation)` → `RemediationPlan`, on 5 pipeline sites), so today **typed records DO cross to the
host** — violating the user's invariant "records are the OSGi vocabulary's implementation; they do
NOT cross to the host". The sealed-ADT compile break (`ResolutionPredicate` record implements the
`ExpectationPredicate` sealed interface) was only the SYMPTOM that surfaced this.

Resolution (user's idea): the pipeline receives the DAGs as **documents (structure)**, and
references a DAG entity by its **path within the document** — a **YAML Path** (`observations[0]`,
`plan.prescriptions[1]`, à la `yq`), NOT a JSON Pointer. YAML is the reference format (operator reads
`pulumi stack output` in YAML; JSON/YAML are the same tree, interconvertible — the in-memory tree
stays a Jackson `JsonNode` via `jackson-dataformat-yaml`, but the EXPOSED address speaks YAML). The
service interface takes **(document ref, path[s])**, never a typed record. OSGi resolves path →
instance in the DAG → runs the logic → returns a result path.

Verified in code: on all 5 sites the host NEVER reads a record's content — `consult()` → it
accumulates the plan (`ConsultationReport`); `recordForCurrentPatient()` → only feeds
`ConsultationNarration` (a log line); `cohortFinding()` → already a `String`. So the host only needs
to ADDRESS nodes, never hold instances. Path-addressing makes position B (records never cross)
practicable WITHOUT killing live consultation.

Consequences:
- `doctor-records` is a PURE OSGi bundle, NEVER system-exported — **NOT a seam** (revises the earlier
  "type=record is seam-like" note). The host never sees it.
- The sealed ADT (`ExpectationPredicate` + `ResolutionPredicate`) lives in `doctor-records` with no
  seam question — it does not cross.
- The seam carries ONLY: the `Document` (structure, already host-side via the mapper) + the
  `DomainDagSource` interface whose methods speak in **paths** (String) + a doc ref.
- The purity guard for `type=record` must accept sealed ADT roots (sealed interface whose permits are
  all records/enums), not only record/enum.
- OPEN (consultation increment): how the host PRODUCES an Observation to inject — build it as
  structure and get a path back, or have OSGi mint it from raw host data (the systemd measurement).

## ★ Mechanism DECISION: DS, not fragment (settled 2026-06-24, user voted B)

The contribution mechanism for a domain's mapper. Two options weighed in the preview:
- **A — fragment carries the YAML mapper.** A fragment shares the host classloader, so its
  `Import-Package` (on the domain's record package) MERGES into the DomainDagMultiplexor host ⇒ the
  DomainDagMultiplexor's import set grows by one record package PER domain. Coupled, grows.
- **B — DS (CHOSEN).** The contract bundle defines a **mapper interface** (`DomainDagMapper`,
  record↔`Document`). The DOMAIN imports only that interface and implements its side as a
  `@Component`, next to its records, in its own bundle. The DomainDagMultiplexor `@Reference`s
  `List<DomainDagMapper>` and imports ONLY the interface package — forever, never grows. Records
  **never leave the domain bundle**; the payload crosses already-serialized as a `JsonNode`.

User's rationale: "keeping the records private makes the system robuster" — blindness is
**structural** (one shared interface package), not policed by a guard. This makes the original
concern ("check the gateway imports only the DAG packages") UNNECESSARY: there is only ever one
import, the interface.

### Reconciliation with [[fragment-contribution-mediation-model]]

That model used a FRAGMENT to contribute a **component/mediator** (code). This uses DS to
contribute **data** (a mapper). Both keep the same principle — a domain contributes its own
capability, meaning is distributed — but: **fragment = mechanism for contributing CODE; DS =
mechanism for contributing DATA** (the payload should cross serialized, not as shared classes).
The fragment proof ([[fragment-contribution-mediation-model]] § PROOF DONE) still stands for the
Specialist/Mediator contribution; the DomainDagMultiplexor is the data-serialization counterpart.

## Second-order win — FEWER live components (user, 2026-06-24)

The prior live-version plan ([[fragment-contribution-mediation-model]]) needed a `@Component` per
participating actor: doctor-core as SCR host, a Mediator per domain, the dynamic
`@Reference List<Specialist>` roster, plus host-published `registerService` wires for EACH pulumi
fact (`SnapshotSource`, `MedicalRecordRegistry`, `InterventionLedgerWriter`, `Patient`). Every
fact-that-crosses needed its own registry-typed service identity.

The DomainDagMultiplexor collapses that to **one service type (`DomainDagMapper`) + one
`@Reference List<DomainDagMapper>`**. A domain publishes ONE `@Component` (its mapper), not a fan
of per-fact components/registrations; data crosses as serialized `Document`s through a single
channel, so it is no longer registry-typed per fact. Live wiring shrinks on two axes — fewer
components, fewer registry types — so **less to wire = less to fail at boot.** This is the runtime
face of the same robustness gain as record-privacy, and it serves the session's origin ask
("lower the number of classes required") at the component level too.

## Bundle shape (target)

- **contract bundle** — `DomainDagMapper` interface + `Document(domain, coordinate, JsonNode)`. The
  ONE shared package both worlds import.
- **doctor-core** — pure records + reasoning, NO OSGi annotations (placeable anywhere).
- **doctor-contribution** — `@Component DomainDagMapper` impl + Jackson; imports the interface;
  maps its LOCAL records. (DS returns here — the "peer-to-peer moment" deferred earlier this
  session; only in `*-contribution`, never in `*-core`.)
- **DomainDagMultiplexor bundle** — `@Reference List<DomainDagMapper>`, mux/demux; imports only the interface.

## Vocabulary moves (the leak cleanup)

- `Patient(org, project, stack)` → keep the word `Patient` but PURGE the "= a Pulumi stack"
  definition; it becomes an abstract diagnosed identity (`Subject` was a candidate rename — likely
  keep `Patient`, just redefine).
- `StackCoordinate(project, stack)` → MOVES to host; it is the adapter's mapping of a Patient/Subject
  to a Pulumi stack.
- `OUTPUT_KEY` / `toOutputMap` / `*Reader` → DELETED (Jackson + Document replace them).
- `Intervention` "Pulumi engine applied" → abstract "an actor applied"; which actor = host knowledge.

## NEXT (agreed sequencing)

1. Author spec `docs/architecture/osgi/multiplexor-spec.adoc` (the 4 preview diagrams graduate in).
2. Atlas update — extend the Doctor subsystem view with a BEFORE/AFTER additivity proof
   (monotone-with-named-erasure: `OUTPUT_KEY`/`toOutputMap`/`*Reader` erased, `StackCoordinate`
   moves out) + a "Verdict — does it hold?".
3. Pattern-doc generalization in `port-edge-domain-ownership.adoc` DEFERRED — doctor is the only
   DAG client today (manifests/netplan/systemd emit flat summaries); don't enshrine a one-client
   abstraction (rule-of-three). Forward-pointer only.

Whiteboard constraint: we REWRITE the stack content; previous Pulumi outputs are not preserved
(no migration shape to honor).

## STATE (2026-06-25) — design closed + stable anchors committed

Two stable commit-anchors on feature/cluster-edge (the user wants every stable state committed, to
reuse as reference like 4e3e1427 was):
- `9c6ad5e8` fix(doctor): restored core/port placement — doctor-core full (13 actors), doctor-port
  thin membrane (50 files, all in doctor.port). Undid bf2e1fde's inversion (it had emptied core +
  dumped actors into port with a package/dir mismatch → wouldn't compile). Pipeline flatten of
  bf2e1fde KEPT (good housekeeping + the type=seam foundation). Verified: full `clean package` green
  without cache.
- `929b3aad` docs(multiplexor): multiplexor-spec + atlas additivity proof (Diagrams N+O) + memory.

Design DOCS now (3 coherent specs + atlas):
- `osgi/multiplexor-spec.adoc` — two-models, Document, DS-over-fragment, redistribution.
- `osgi/world-boundary-spec.adoc` — THE single door between worlds (NOT a pattern, an arch spec):
  type-seam + `awaitService` + DS-publish + sealed impl; "one DATA door (DomainDagMultiplexor) + a distinct
  PROBE door (SystemdRuntimeProbe)". Holds the CONCENTRATION CHECK (below). + pending commit.
- `integration-atlas.adoc` — two-vocabulary table + Doctor 3rd additivity proof.

★ CONCENTRATION CHECK — the falsifiable quality indicator for the increment (in world-boundary-spec):
the pipeline is the host-side ACL (Anti-Corruption Layer — same nature as the `unitrepo-pulumi` ACL
the atlas already names). Two greps are the acceptance criteria:
1. `grep -rl "import com.pulumi" osgi/` == 0  → TRUE today (type-level invariant holds).
2. `grep -rl "toOutputMap\|OUTPUT_KEY" osgi/` == 0 → **14 today** (doctor records self-serialize —
   the leak the increment closes). Baseline map: door OSGi→host (awaitService) ALREADY concentrated
   in controlplane/pipeline/ (7 files); translation TRANSPIRED over ~30 files (14 in doctor/port/,
   ~11 host) that must converge into per-domain DomainDagMappers + the one ACL. Run on every commit.

★ type=record DECISION (user chose C): introduce a NEW embed `type=record` (first-class category,
not just a package/bundle) for pure-data bundles, alongside model/seam/edge/fixture. The user wants
the system to break on violation ("benefice pur"). Resolver enforces PLACEMENT already; PURITY
(only records/enums exported, zero behavior) needs a GUARD = build-time, in the staging extension
(StagingClosure reads the bnd; bytecode check Class.isRecord()/isEnum()). Both in the same increment.

★ DoctorConsultingService is the OPEN knot: it is NOT data — it's a live synchronous `consult()` on
**5 call sites** in the pipeline (SystemdAdapterStage, ClusterReadinessStage, ResourcesStage,
ResourceManager, ResourceCreationPipeline; assembled in DoctorAssembly, held in PipelineState). So
its `-port` SURVIVES as a probe/consultation door UNLESS we move consultation to across-runs (then
it becomes data → joins the DomainDagMultiplexor). The data `-port`s (SnapshotSource, MedicalRecordRegistry,
InterventionLedgerWriter) DO dissolve into the DomainDagMultiplexor. Decision in-process-vs-across-runs still
deferred — it touches 5 pipeline sites, not trivial.

NEXT (incremental roadmap — each step green + commit, never hold the whole in head):
1. `doctor-records` bundle + `type=record` + the staging-ext purity guard → green, commit.
2. contract bundle (`DomainDagMapper` + `Document(domain, coordinate, JsonNode)`) → green, commit.
3. `doctor-contribution` (one DomainDagMapper, Jackson; `.internal` package for the impl) → commit.
4. DomainDagMultiplexor sealed bundle + `@Reference List<DomainDagMapper>` + the SCR bind boot test (sibling
   proof = `FragmentContributedComponentTest`) → green, commit.
5. host `DomainDagAdapter` (the ACL) + `awaitService(DomainDagSource.class)` → preview green, commit.
   ALSO at step 5: convert `ConsultationLog` (today a mutable shared accumulator threaded by ref
   through ~6 pipeline stages, `.record()` mutated at SystemdAdapterStage + ClusterReadinessStage,
   read at ResourceCreationPipeline + RunbookRenderer) into an IMMUTABLE record with `withReport()`
   — DECIDED (user, the immutability rule), DEFERRED to here because it forces re-threading the
   return value through the stages, which is pipeline-refactor scope, NOT a step-1 pure move.
6. SEPARATE later decision: DoctorConsultingService in-process vs across-runs (the 5 sites).
Pattern-doc forward-pointer in `port-edge-domain-ownership.adoc` still NOT done (low priority).
Whiteboard: we REWRITE the stack content; no prior Pulumi outputs preserved.

See [[fragment-contribution-mediation-model]] [[doctor-internal-edge-debt]]
[[pipeline-orchestration-osgi-vision]] [[external-edges-chantier-handoff]].

## BACKLOG — factory-as-instance refinement (user, 2026-06-25)

A static factory (DoctorGraph.assemble, HealthSystem.admit) is a LEGITIMATE static (CLAUDE.md
exception). But it can still be inverted: materialize the factory as an INSTANCE that delegates
creation. The gain is NOT the creation itself — it is that the factory becomes a NODE in the object
graph: if the created instance holds a reference back to its factory, then walking UP the tree from
any instance reaches the factory (and what it knows) — navigable from anywhere. A static factory is
invisible in the graph; a factory-instance is navigable. Aligns with keep-the-graph-navigable
([[prefer-non-static-inner-keep-the-graph]]). Deferred refinement, not blocking; revisit with the
static-helper audit.
