---
name: multiplexor-two-models-design
description: "DESIGN settled 2026-06-24 (brainstorm, NOT yet built) — the host↔OSGi data exchange is reframed as TWO distinct world-models (OSGi diagnostic model vs host stack model) meeting ONLY at a Document contract, never sharing a type. A Multiplexor (OSGi) mux/demuxes an envelope stream; a host PipelineAdapter mirrors it to Pulumi. Mechanism = DS (chosen over fragment), so records stay private. Invariant: Pulumi is host-only vocabulary."
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
| **Multiplexor** NEW | OSGi | envelope `(domain, coordinate)`; mux/demux a transport-neutral DocumentStream | no — a stream codec |
| **PipelineAdapter** NEW | host | maps DocumentStream ↔ Pulumi outputs; live stack I/O | yes — the ONLY one |

Egress/ingress are MIRRORS meeting at the stream: domain DAG → Multiplexor (stamp+mux) →
DocumentStream → PipelineAdapter (export per `domain/coordinate`) → Pulumi; and the reverse.
Pulumi becomes ONE possible sink behind the adapter — the same stream could later land in a git
repo (the "clone the stack onto git, immutable" idea), Multiplexor unchanged.

## Operator-legibility constraint (decides the wire shape)

The operator must read the stack with plain `pulumi stack output` (NO rke2lab tooling) and
understand it. ⇒ the **envelope rides in the output KEY** (`doctor/consultationReport`), the
**value is the bare DAG** (Pulumi-native structured JSON, diffable). NO `schema:`/`payload:`
wrapper in the operator's face; NO opaque multi-doc YAML blob (kills Pulumi diff — rejected).
Optional per-domain `domain/summary` shallow human line alongside the deep DAG.

## ★ Mechanism DECISION: DS, not fragment (settled 2026-06-24, user voted B)

The contribution mechanism for a domain's mapper. Two options weighed in the preview:
- **A — fragment carries the YAML mapper.** A fragment shares the host classloader, so its
  `Import-Package` (on the domain's record package) MERGES into the Multiplexor host ⇒ the
  Multiplexor's import set grows by one record package PER domain. Coupled, grows.
- **B — DS (CHOSEN).** The contract bundle defines a **mapper interface** (`DocumentMapper`,
  record↔`Document`). The DOMAIN imports only that interface and implements its side as a
  `@Component`, next to its records, in its own bundle. The Multiplexor `@Reference`s
  `List<DocumentMapper>` and imports ONLY the interface package — forever, never grows. Records
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
Specialist/Mediator contribution; the Multiplexor is the data-serialization counterpart.

## Second-order win — FEWER live components (user, 2026-06-24)

The prior live-version plan ([[fragment-contribution-mediation-model]]) needed a `@Component` per
participating actor: doctor-core as SCR host, a Mediator per domain, the dynamic
`@Reference List<Specialist>` roster, plus host-published `registerService` wires for EACH pulumi
fact (`SnapshotSource`, `MedicalRecordRegistry`, `InterventionLedgerWriter`, `Patient`). Every
fact-that-crosses needed its own registry-typed service identity.

The Multiplexor collapses that to **one service type (`DocumentMapper`) + one
`@Reference List<DocumentMapper>`**. A domain publishes ONE `@Component` (its mapper), not a fan
of per-fact components/registrations; data crosses as serialized `Document`s through a single
channel, so it is no longer registry-typed per fact. Live wiring shrinks on two axes — fewer
components, fewer registry types — so **less to wire = less to fail at boot.** This is the runtime
face of the same robustness gain as record-privacy, and it serves the session's origin ask
("lower the number of classes required") at the component level too.

## Bundle shape (target)

- **contract bundle** — `DocumentMapper` interface + `Document(domain, coordinate, JsonNode)`. The
  ONE shared package both worlds import.
- **doctor-core** — pure records + reasoning, NO OSGi annotations (placeable anywhere).
- **doctor-contribution** — `@Component DocumentMapper` impl + Jackson; imports the interface;
  maps its LOCAL records. (DS returns here — the "peer-to-peer moment" deferred earlier this
  session; only in `*-contribution`, never in `*-core`.)
- **Multiplexor bundle** — `@Reference List<DocumentMapper>`, mux/demux; imports only the interface.

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

## RESUME TOMORROW (2026-06-24 EOD — hotspot died)

DONE today, on disk, NOT committed (working tree still mid-flight from the lost session
77d9e53b — see `git status`; the stray default-package `Grant.java` + the doctor-port/core churn
are pre-existing, untouched by today's design work):
- `docs/architecture/osgi/multiplexor-spec.adoc` (337 lines) — the spec, 4 graduated diagrams.
- `docs/architecture/integration-atlas.adoc` — two additions: (1) "two vocabularies" table in the
  "two spaces" section (stage column = TODAY only, future stages honestly deferred); (2) Doctor view
  3rd additivity proof "Multiplexor — two models" (Diagrams N+O + verdict rated GO, risks LOW).
- `.claude/claude-preview.adoc` — last render (fragment-vs-DS option compare); safe to overwrite.

Settled this session (all in this file above): two-models reframe; `Document(domain, coordinate,
JsonNode)` contract; **DS mechanism chosen over fragment (user voted B — records private)**; naming
= `Patient` (doctor side) ↔ `Stack`/`StackCoordinate` (host side), same thing per world; operator
reads bare DAG via envelope-in-the-output-KEY; component redistribution
`OutputBuilder`→Multiplexor, `ReadinessOutputMapper`→`DocumentMapper`, `OutputsStage` stays host;
**model + pipeline refactor are ONE atomic increment** (the boot pipeline IS the `pulumi preview`
orchestrator — half-landed won't preview green).

NEXT (where to pick up):
1. Pattern-doc forward-pointer in `port-edge-domain-ownership.adoc` (generalization DEFERRED — still
   one-client; just a pointer to the spec). NOT yet done.
2. THEN start the increment build: contract bundle (`DocumentMapper` + `Document`) → doctor records
   pure (delete `toOutputMap`/`OUTPUT_KEY`/4 `*Reader`s) → `doctor-contribution` `@Component` +
   Jackson → Multiplexor + `@Reference List<DocumentMapper>` → host `PipelineAdapter` → pipeline
   re-slice. CARRY the boot test: SCR binds `@Reference List<DocumentMapper>` (sibling proof =
   `FragmentContributedComponentTest`).
3. Open/LOW-risk: refactored stage names (defer — mechanical re-slice in the fluent grammar).

See [[fragment-contribution-mediation-model]] [[doctor-internal-edge-debt]]
[[pipeline-orchestration-osgi-vision]] [[external-edges-chantier-handoff]].
