---
name: pipeline-state-per-topic-io-refactor-backlog
description: "DESIGN CONVERGED 2026-07-03 (whiteboard .claude/claude-preview.adoc), materialization STARTED. The fluent-pipeline state becomes generic per topic — State<I,O> where each topic declares a narrow input record I (only what it consumes) + output record O; the transition folds O_n into I_{n+1}. Ambient/transverse data goes through a generalized PipelineContext registry (Class→record, lifted from IncusResourceBootstrap.ContextRegistry), READ/WRITTEN ONLY when a transition builds an input — never inside a topic, which keeps each topic's input DETERMINISTIC. Backref chain REJECTED. Census of all 7 rke2lab pipelines done."
metadata:
  node_type: memory
  type: project
---

## Status (2026-07-03)

Design **converged** on the whiteboard `.claude/claude-preview.adoc` (full C4/Mermaid there — read it
for the diagrams). Materialization **started**: `Topic<I,O>` + `PipelineContext` in the pipeline-port.
This memory is the durable summary so we don't re-litigate.

## The decided shape — generic `State<I,O>` + ambient `PipelineContext`

Each topic declares its own **input record `I`** (only what it consumes) and **output record `O`**
(only what it produces). The transition folds `O_n` into `I_{n+1}` — "the output of the current state
is the builder of the next", now lifted to the TYPE level.

```java
final class State<I, O, B> {       // State<I,O,Builder> — the user's 3-param shape (2026-07-02)
  final I inputs;                  // per-topic (MOVES)    — flux + ambient FUSED into I at the transition, safe alone
  final B builder;                 // per-pipeline (FIXED) — set-once accumulator of the O's = the builder of the next state
  final PipelineContext context;   // ambient registry — touched ONLY by the transition
  final FluentTopicRunner runner;
}
interface Topic<I, O> { O run(I inputs); }   // sees ONLY its frozen I — no context → deterministic
```

**`B` IS parameterized (user, 2026-07-03).** `State<I,O,B>`, not `State<I,O>` over a raw `StateBuilder`
base — else the shared contract can't type the `O → builder` fold. Kind asymmetry: `I`/`O` MOVE (the
mutable pair each transition rebuilds); `B` is FIXED for the whole pipeline (it grows as outputs fold in
but its type never changes). Parameterizing `B` makes the sink `Consumer<O>` folding into `B` — "output
of the current state = builder of the next" holds at the TYPE level. This resolves the open "single
accumulator vs last-O-only" question toward the SINGLE ACCUMULATOR: `B` carries all outputs so far, and
a fan-in topic's input is built by reading several outputs off `B` at the transition.

**Naming — the `Pipeline` prefix is top-level only (user, 2026-07-03).** A type carries `Pipeline` only
when top-level and the prefix qualifies it (`PipelineContext`, `ClusterSeedPipeline`). Inner
classes/interfaces/records inherit the qualifier from the outer pipeline, so the prefix is redundant
there: use `State` (NOT `PipelineState`), `StateBuilder`, `Launch`, `Awaiting<Topic>`/`<Topic>Done`. The
old flat `PipelineState`/`PipelineInputs` names drop to `State` / the input record's own name on
migration. `Topic<I,O>` and `PipelineContext` are top-level port types (no enclosing class).

**Two roles, never conflated:**
- **flux** (data produced by an upstream topic) → carried by the record `I`, compile-time, `@NonNull`
  by construction, built explicitly at the transition. A miss is impossible.
- **ambient** (transverse, known before the first topic: modes, orchestrators, services, charts) →
  the `PipelineContext` registry, `require(Type.class)` runtime-checked. A miss = mis-wiring caught at
  the first run (fail-fast), never an "order-dependent not-yet-produced".

## THE determinism rule (user, 2026-07-03) — the load-bearing insight

The context is a mutable `Class→record` aggregator; nothing technically stops a topic from WRITING a
record mid-flow. If it could, a topic's input would depend on the runtime context state at execution
time → **non-deterministic**, order-coupled — the exact flat-bag disease we flee, just wearing a clean
type. So: **the context is read AND written ONLY when a transition builds an input. Never inside a
topic.** Consequences: (a) the topic does NOT see the context — signature `run(I)`, not
`run(I, context)`; (b) the transition is the single point of contact (read flux + read ambient → fuse
into a frozen `I` → optionally write newly-available ambient for later transitions); (c) flux and
ambient meet inside `I`, and where each field came from is a transition implementation detail the topic
need not know. This turns a *typed* contract into a *typed + deterministic + localized* one — that's
what makes the pattern hold over time, not just compile.

## What's REJECTED (don't reopen)

- **Backref chain** (walk previous states, lookup-by-input-class): answers a non-adjacent access that
  NEITHER real pipeline has; a miss is a runtime throw that would weaken the flux's compile-time
  guarantee. Rejected in favour of the record `I` built explicitly at each transition.
- **Typed-map lookup for flux**: same runtime-miss trade. The map/registry stays for AMBIENT only.
- **Recursive generics `State<I,O,Prev>` (HList)**: illegible in Java, fights the "clear roles" benefit.
- **Factoring the transition classes** (a generic `runDuring` on the shared state + `(R) this` cast):
  the grammar doc's own Pitfall — the type-state IS the deduplication. `State<I,O>` is a per-pipeline
  SHAPE; the transition classes stay explicit and hand-written per pipeline.

## What IS shared at the pipeline-port (the materialization)

- `Topic<I,O>` — the `O run(I)` contract (deterministic, no context param).
- `PipelineContext` — the ambient registry, generalized from
  `controlplane.incus.ContextRegistry` (register/require/update/lookup/contains, `Class→record`,
  fail-fast `require`). `IncusResourceBootstrap` becomes a CONSUMER of this port type, not its owner —
  its local `ContextRegistry` is then deleted (no legacy variant, CLAUDE.md uniformity).
- `FluentTopicRunner` / `OnFailure` / `TopicFailure` are ALREADY generic-ready (`<S> runDuring(...)`) —
  no change needed.

## The census — all 7 rke2lab pipelines (scan 2026-07-03)

APPLIES: `ApplicationPipeline` (study case, linear), `ClusterSeedPipeline` (flux + fan-in at
ResourcesTopic), `IncusResourceBootstrap` (applies best — already on ContextRegistry),
`DefaultManifestSynthesisService` (proto-State<I,O>: named outputs Scaffold/Registry/Targets +
fan-in at SystemdUnitsStage). APPLIES-WITH-RESERVE: `TargetChecksumPipeline` (single accumulator
`targetChecksums` — I/O records oversized). DIVERGES/NA: `SystemdInfrastructureSynthesizer` (topics are
side-effect builders into SystemdChart, implicit fan-in via ctor injection — retrofit below),
`FrameworkLaunchPipeline` (degenerate single-`during("boot")` preset). `ResourceCreationPipeline` =
plain staged builder (`withX().build()`), outside the type-state pattern.

**Key finding — the OTHER generalizable logic:** three ad-hoc ambient contexts were each reinvented —
`ContextRegistry` (incus), `ManifestSynthesisContext` (thread-local, manifests),
`SystemdSynthesisContext` (systemd). The unified `PipelineContext` replaces all three.

## Two non-conformities — DECIDED (user 2026-07-03)

1. **`SystemdInfrastructureSynthesizer` retrofit — IN the atomic wave.** Today the flux carrier is the
   topic INSTANCES themselves (State threads `toolsTopic`/`rke2InstallTopic`; downstream topics take
   them by ctor + read via `@Nullable`+`requireNonNull` getters `getFloxInstallService()` etc.). Only a
   handful of `SystemdService` handles actually cross a topic boundary; the rest is pure side-effect
   into `SystemdChart`. Flux traced: Tools exposes nixInstall/floxInstall; Rke2Install consumes Tools,
   exposes bootstrapEnv/install; Network consumes Rke2Install; Storage consumes Tools + Rke2Install
   (FAN-IN). Retrofit: output records `ToolsOutput(nixInstall, floxInstall)`,
   `Rke2InstallOutput(bootstrapEnv, install)` (Network/Storage → Void); input records `Rke2InstallInput(ToolsOutput)`,
   `NetworkInput(Rke2InstallOutput)`, `StorageInput(ToolsOutput, Rke2InstallOutput)`; each topic keeps
   its `this`-returning verbs + gains a `toOutput()` terminal, public getters DELETED; ambient
   (`SystemdChart`, `SystemdSynthesisContext`) moves to `PipelineContext`. Gain: cross-topic deps become
   explicit + compile-time. Cost: real chantier (4 topics + synthesizer rewritten), side-effect into
   SystemdChart stays.
2. **`FrameworkLaunchPipeline` — materialize the 3 topics.** The topics EXIST in the logic (merged, not
   a javadoc fiction): `bootEmbedded()` chains `BootPlanner.plan(BootRequest…)` (discovery + plan, PURE)
   then `FrameworkLauncher.launch(plan)` (EFFECT) — the pure/effect seam the javadoc describes is real,
   but collapsed into one `during("boot")`, and there is NO `PlanDone` class though the javadoc promises
   "inspect the pure plan at PlanDone without booting Felix". Decision: materialize discovery→plan→launch
   as a real type-state entry (delivering the promised inspectable `PlanDone`); `embedded()` stays the
   preset shortcut on top, javadoc corrected (it chains, it does not expose states).

## Pilot = `IncusResourceBootstrap` (user, 2026-07-03) — richest case reveals the hole

Pilot is NOT the linear `ApplicationPipeline` — deliberately the RICHEST pipeline, to surface what a
linear pilot would hide (user: "en prendre un plus riche/complexe comme premier cas d'étude, ça
révèlera ce qu'on aurait pu rater"). It exercises every hard axis: real ambient registry, 10-field
accumulator, fan-in, AND a mid-flow registry write.

**The hole it revealed (before writing code):** `ApplyState.registry` (the `ContextRegistry` we planned
to generalize) is a fourre-tout — ONE genuine ambient (`ControlplanePolicy`, registered in `apply()`
before topic 1) + FOUR outputs (`DeploymentMetadata`, `ProvisioningMetadata`, `BuildMetadata`,
`RuntimeMetadata`, produced by Host/Provider stages). The prototype we wanted to lift was itself
conflating flux and ambient. Under the design: ambient → `PipelineContext`, outputs → accumulator `B`.
And `ProviderStage.ensureImage`'s `registry.update(BuildMetadata)` mid-flow (the motion the determinism
rule forbids) is NOT a legitimate ambient enrichment — `BuildMetadata` is a TWO-CONTRIBUTOR output
(Host: manifests, Provider: imageChecksum). In `State<I,O,B>` it splits into two distinct outputs fused
at the fan-in (`toResult`); the `update()` disappears as the symptom it was.

**Decision (user 2026-07-03): materialize `PipelineContext` WITHOUT `update()`** (register/require/
lookup/contains only). Revisit whether a legitimate ambient-`update` need exists at the A5 retro —
decide empirically on what the conversion shows, not on hypothesis. This is why the pilot matters: the
`update()` question would never have surfaced on the linear pipeline.

## Sub-pipelines + the three topic-body natures (user, 2026-07-03)

**Sub-pipeline pattern (`pipeline → sub-pipeline1, sub-pipeline2, …`).** A topic whose body is itself a
`during/then` chain, with its OWN `State<I,O,B>` (own `B`, own topics), returning ONE composite output
to the parent. The parent sees one slot in its `B`, one `O`; the sub-pipeline encapsulates its
complexity → the parent's `B` stays small, each `I` narrow. Not speculative — the codebase ALREADY
nests: `ApplicationPipeline`→`ClusterSeedPipeline`; `ClusterSeedPipeline`→`ResourceManager`→
`ResourceCreationPipeline`; `DefaultManifestSynthesisService`→`SystemdInfrastructureSynthesizer`. We
just name the pattern. **Port rule:** a sub-pipeline SHARES the parent's `PipelineContext` (ambient is
common: config/policy/services) but its `B` is strictly local.

**Three natures a topic's body can have** (answers "do we have jgiven's scenarios in the pipeline
model?" — yes, as a topic BODY, not a topic):
- *execution* — does a gesture, produces output `O` (Path, Provider, Instance, environment, outputs).
- *checkpoint (narrated)* — runs a jgiven scenario → a `ReportModel` narrative + verdict. Only TWO
  today: `ClusterReadinessTopic`, `SystemdAdapterTopic` (via `*Scenario extends Stage<>`). "One engine
  (JGiven), two layers": `during/then` orchestrates, jgiven narrates INSIDE a topic. Narration serves
  the operator (runbook) — put it where a human must READ the result, not everywhere.
- *sub-pipeline* — body is itself a `during/then` chain (own `B`).
All three share ONE orchestration contract `Topic<I,O> { O run(I); }` (all `FluentTopicRunner` sees), but
checkpoint + sub-pipeline get an abstract BASE above it (execution stays plain `Topic`). The base does
TWO things at once — the reconciliation of the two visions we debated (user 2026-07-03): (1) IDENTIFY
the nature (`nature()`/`role()` — the topic's role in the pipeline is readable at a glance, the thing
that guides the retrofit), and (2) HOST the ceremony proven identical across instances. Verified by
reading BOTH checkpoints (`SystemdAdapterTopic` + `ClusterReadinessTopic`): the ceremony IS byte-identical
— `preview` flag, `reportModel=runbook.orElseGet`, the `JGIVEN_DRY_RUN` save/set/restore, the
`Scenario.create→setModel→startScenario→try{script}finally{finished}catch{failure}` skeleton, the
`consult→log→record` plumbing — so a `CheckpointTopic<I,O>` base hosts it, leaving 2 hooks for the
genuine divergence: `playScript` (given/when/then) + `outcome` (systemd `ReadinessAuthority` STOP/degraded
vs cluster `VerificationResult` projection). `run(I)` is `final` on the base → the concrete topic supplies
only what diverges, NEVER re-implements orchestration (this is why base, not interface-with-defaults: the
ceremony needs STATE — captured observation, dry-run save/restore — a default method can't hold it). The
scenario collaborators (`ReportModel`/doctor/authority) are AMBIENT → base pulls from `PipelineContext`
(consistent with the determinism rule). jgiven stays a narration engine CALLED from the base, never a
rival of `during/then`. **CAVEAT (challenge, keep honest):** N=2 checkpoints; a base with ≥3 hooks for 2
impls is a leaky abstraction — validate at A5 that hooks stay ≤~2, else keep the duplication. Same for
`SubPipelineTopic<I,O>` (hosts "launch sub-chain, share ambient, local B").

## DECISION — Incus re-decomposed by phases (Option D, user 2026-07-03)

Do NOT take Incus's 4 inherited topics as given. Re-cut by domain sense into 3 phases, each a
sub-pipeline (own `B`, shared ambient `PipelineContext`). Parent Incus becomes `prepare → provision →
launch`:
- *PREPARE* (sub-pipeline): resolvePaths → synthManifests → stageAssets → captureMetadata → syncToHost.
  Returns `PreparedHost`(localPaths, nixosPaths, deployment, provisioning, runtime, manifests). DISSOLVES
  the HostStage monster — its 4 metadata + synth/staging/sync become PREPARE's internal topics, in
  PREPARE's LOCAL `B`, invisible to the parent.
- *PROVISION* (sub-pipeline): ensureProject → ensureNetworks → ensureProfile → ensureImage →
  imageStateConfigMap. Returns `ProvisionedResources`(providerContext, projectName, profileName,
  imageFingerprint, imageChecksum). The image fan-in / former `BuildMetadata.update()` stays LOCAL to
  PROVISION — no mid-flow update, no leak to parent.
- *LAUNCH*: createInstance, fan-in reading `PreparedHost` + `ProvisionedResources`. Returns
  `LaunchedInstance`(instance). OPEN: LAUNCH as a 1-topic sub-pipeline (uniformity) vs a leaf execution
  topic in the parent (pragmatism) — lean leaf unless a 2nd gesture emerges.

Parent `B` = 3 composite slots instead of 10 flat fields. This is the concrete payoff of the
sub-pipeline pattern for the richest case, and it is why the pilot is Incus (the decomposition need only
surfaces on a rich pipeline).

## Still to settle (final design, before/while materializing)

- Where `PipelineContext` lives: pipeline-port (`osgi/foundation/pipeline`, beside FluentTopicRunner) —
  chosen; `IncusResourceBootstrap.ContextRegistry` then deleted.
- flux/ambient boundary rule: *produced by a topic → flux (record I); known before the first topic →
  ambient (registry)*. Verify nothing order-conditional leaks into the registry (e.g. `outputBuilder`,
  `bboxOrchestrator` are ambient — wired at launch).
- `StateBuilder` vs fan-in: single accumulator carrying the outputs (proposed) vs each transition
  carrying only the last O. Single accumulator is simpler; confirm it doesn't recreate a mini-bag.
- Scope of the atomic wave: `ApplicationPipeline` first as proof, then the rest — or migrate all at once
  (CLAUDE.md uniformity, no "legacy" variant).

See [[null-safety-set-once-fields-monotonic]] [[null-safety-optional-from-source-to-resolver]]
[[options-always-as-c4-diagrams]] (the whiteboard is where this was drawn).
