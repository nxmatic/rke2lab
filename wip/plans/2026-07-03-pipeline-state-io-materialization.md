# Pipeline `State<I,O,B>` + `PipelineContext` — Materialization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:executing-plans (or subagent-driven-development) to implement task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lift the fluent-pipeline state to the type level — a generic `State<I,O,B>` per topic (narrow input record `I`, output record `O`, fixed accumulator `B`) plus a shared ambient `PipelineContext` registry, with the determinism rule (context read/written ONLY when a transition builds an input; a topic sees only its frozen `I`). Replaces the flat `PipelineState`/`PipelineInputs` sack and the three reinvented ad-hoc ambient contexts.

**Design of record:** `docs/architecture/patterns/fluent-pipeline-grammar.adoc` (State-shape + determinism rule + `Pipeline`-prefix naming rule) and `docs/architecture/osgi/pipeline-spec.adoc`. Whiteboard where it converged: `.claude/claude-preview.adoc`. Memory: `pipeline-state-per-topic-io-refactor-backlog` (full census + rejected alternatives). This plan is design-first: the atlas is already aligned (ahead of code).

**Strategy — pillar-first, break-all, then generalize (user, 2026-07-03).** Materialize the port contract first — it breaks every consumer, assumed. Let red compilation enumerate the call sites (no guessing the scope). Then convert ONE pilot pipeline, stabilize the contract on it, and only then align the rest. "On matérialise la nouvelle interface dans port et tout casse, on choisit un premier pipeline et on implémente, on adapte sur le premier cas et on généralise, alors on aligne les autres."

**Pilot = `IncusResourceBootstrap` (user, 2026-07-03), NOT the linear `ApplicationPipeline`.** Deliberately the RICHEST pipeline, to reveal what a linear pilot would hide. It exercises every hard axis at once: a real ambient registry (already `ContextRegistry`), a 10-field accumulator, a fan-in (`InstanceStage`/`toResult` read several outputs), AND — the revealer — a **mid-flow registry write** (`ProviderStage.ensureImage` does `registry.update(BuildMetadata)`), the exact motion the determinism rule forbids.

**Hole the pilot already revealed (before writing a line).** `ApplyState.registry` is a fourre-tout: ONE genuine ambient (`ControlplanePolicy`, registered in `apply()` before the first topic) + FOUR outputs (`DeploymentMetadata`, `ProvisioningMetadata`, `BuildMetadata`, `RuntimeMetadata`, all produced by HostStage/ProviderStage). Under the converged design: ambient → `PipelineContext`, outputs → the accumulator `B`. The `registry.update(BuildMetadata)` mid-flow (l.753-760) is NOT a legitimate ambient enrichment — it is a two-contributor OUTPUT (Host produces `manifests`, Provider produces `imageChecksum`) mislodged in the registry. In `State<I,O,B>` these become two distinct outputs fused at the fan-in (`toResult`); the `update()` disappears. **Decision (user 2026-07-03): materialize `PipelineContext` WITHOUT `update()` (register/require/lookup/contains only); revisit whether a legitimate ambient-`update` need exists at the A5 retro — decide empirically on what the conversion shows, not on hypothesis.**

**Relationship to the OSGi-ownership plan.** Adjacent, not the same. `2026-07-02-pipeline-osgi-ownership.md` is Thread S (structure: `*Stage`→`*Topic`, decision-into-OSGi) + Thread R (reliability). Its **S3** injects run data via a context — THIS plan supplies the `PipelineContext` that S3 will inject. Do them so S3 consumes this plan's `PipelineContext` rather than inventing `TopicContext`. This plan is transverse to the 7 pipelines; keep it independent, sequence-after where they touch.

## Blast-radius (verified 2026-07-03 — the break-all is CONFINED to seed-master)

Checked before materializing, to be sure the reactor reaches the pipeline we refactor:
- **`ContextRegistry`** — 2 files, both in `controlplane/incus/`, both in seed-master. Deleting it breaks nothing upstream.
- **Incus records** (`DeploymentMetadata`/`ProvisioningMetadata`/`BuildMetadata`/`RuntimeMetadata`/`BootstrapResult`) — every referent is inside `exec/seed-master/` (packages `incus`/`resources`/`pipeline`). No `osgi/`, `sdks/`, `host/`, `netplan/` module sees them.
- **Reactor order:** `pipeline-port` [9/69] (upstream, additive, already green) → `seed-master` [65/69]. The 4 modules after seed-master (`netplan-cli`, `manifests-cli`, `exec`, `rke2lab`) do NOT depend on seed-master (only `build-parent` names it, in dependencyManagement). So: nothing upstream blocks reaching seed-master; nothing downstream propagates the break.
- **The only break-all is intra-seed-master** (e.g. changing `BootstrapResult` breaks the 6 files consuming it — `PipelineState`, `OutputBuilder`, `ResourcesTopic`, `IncusTopic`, `ResourceManager`, `ResourceCreationPipeline` — all in one module, seen at once by javac; no reactor barrier).
- **Practical loop:** iterate with `-pl :seed-master -am` (rebuilds the green port upstream, confines the break); keep full `-Pall-worlds` for final validation only.

## Global Constraints

- **Build through flox always:** `flox activate -- ./mvnw …`. Never `mvn install` to `~/.m2`; inter-module deps resolve through the reactor — every module build uses `-am`. Build `-Pall-worlds` (NEVER `,nxmatic`). Measure NullAway with `clean package -DskipTests=true -Dmaven.build.cache.skipCache=true` (cache-off is mandatory or warnings don't re-emit).
- **seed-master uses `package`, not `compile`** (the `stage-embedded-bundles` goal needs it).
- **NullAway gate ON** (severity ERROR by default). New port types must be ERROR-clean. Use `@MonotonicNonNull` for set-once, `Optional` for genuine absence, `@Nullable` only with explicit user consult.
- **Own external worktree per this chantier** (external model, sibling of `main`, NOT `.claude/worktrees/`). Re-smudge sops on creation.
- **No blind sweeps.** Renames are scoped explicitly per task (word-boundary regex, module-scoped). A blind `perl -i` clobbered manifests-core earlier this session — reverted.
- **Naming rule (project-wide, adopted 2026-07-03):** an INNER type never repeats a token of its enclosing type — the outer supplies the qualifier. So `State` not `PipelineState`, `Inputs` not `PipelineInputs`. Top-level/port types keep a qualifying prefix where it disambiguates (`PipelineContext`, `Topic`). See the follow-up sweep (F1) for non-pipeline violations — OUT of this wave's scope.

## Phase P — the port contract (break-all)

- [ ] **P1 — Materialize `Topic<I,O>` in `pipeline-port`.** `@FunctionalInterface interface Topic<I,O> { O run(I inputs); }` in `osgi/foundation/pipeline/pipeline-port/.../pipeline/`. Javadoc: sees only its frozen `I`, no context → deterministic; flux + ambient fused into `I` by the transition. `@NullMarked` package. Build pipeline-port alone → green (no consumer yet).
- [ ] **P2 — Materialize `PipelineContext` in `pipeline-port`.** Generalize `controlplane.incus.ContextRegistry`: `Class→record` registry, `register`/`require`/`lookup`/`contains` — **NO `update()`** (decision above; the only current `update` usage is the mislodged-flux anti-pattern). Fail-fast `require`, `type.cast` (not unchecked). Javadoc: AMBIENT only (never flux); read/written ONLY while a transition builds an input; the determinism discipline. Build pipeline-port → green.
- [ ] **P3 — Confirm `FluentTopicRunner`/`OnFailure`/`TopicFailure` need no change.** They are already generic (`<S> runDuring(...)`). Verify `State<I,O,B>` threads through `runDuring` unchanged; if a signature tweak is truly needed, decide here (design-first) before touching. Likely no-op.
- [ ] **P4 — Topic natures: identification + shared-ceremony bases.** `Topic<I,O>` stays the plain orchestration SAM (execution topics use it directly). Add `TopicNature {EXECUTION, CHECKPOINT, SUB_PIPELINE}` + a base that BOTH identifies (`nature()` default-by-subtype, `role()`) AND hosts the proven-identical ceremony:
  - `CheckpointTopic<I,O>` — `run(I)` `final`, hosts the jGiven ceremony verified byte-identical across `SystemdAdapterTopic`+`ClusterReadinessTopic` (preview flag, `reportModel=runbook.orElseGet`, `JGIVEN_DRY_RUN` save/set/restore, `Scenario.create→setModel→startScenario→try{script}finally{finished}catch`, `consult→log→record`); 2 hooks `playScript(...)` + `outcome(...)`. Collaborators (`ReportModel`/doctor/authority) pulled from `PipelineContext` (ambient). Base not interface — the ceremony needs state.
  - `SubPipelineTopic<I,O>` — hosts "launch a sub-chain sharing ambient, keep a local `B`" (used by the Incus PREPARE/PROVISION phases in Phase A).
  - **CAVEAT — validate at A5:** N=2 checkpoints; if `CheckpointTopic` grows ≥3 hooks it is a leaky abstraction — keep duplication instead. The ceremony justifies the base; the divergent outcome stays a hook. The 2 checkpoints are actually migrated onto `CheckpointTopic` in Phase G1 (they belong to `ClusterSeedPipeline`), not here — P4 only materializes the bases.

> After P: the port carries the contract. Nothing consumes it yet, so the reactor still builds. The "break-all" happens when the pilot adopts it and we delete the flat types (Phase A).

## Phase A — pilot: `IncusResourceBootstrap`, re-decomposed into 3 sub-pipelines (Option D)

The richest case: a real registry, a 10-field accumulator, a fan-in, the mid-flow-write hole. **Decision D (user 2026-07-03): do NOT keep the 4 inherited topics — re-cut by domain sense into 3 sub-pipelines** `prepare → provision → launch`, each with its own local `B`, sharing the parent's ambient `PipelineContext`. Parent `B` = 3 composite slots instead of 10 flat fields. This exercises the sub-pipeline pattern (the reason a rich pilot was chosen) AND dissolves the HostStage monster.

- [ ] **A1 — Design the records for the 3 sub-pipelines + parent (design-first, before code).**
  - *Ambient (`PipelineContext`, registered in `apply()`, shared by parent + all sub-pipelines):* `BootstrapContext` (config + services), `ControlplanePolicy`.
  - *PREPARE sub-pipeline* (local `B`): topics resolvePaths → synthManifests → stageAssets → captureMetadata → syncToHost. Returns `PreparedHost`(localPaths, nixosPaths, deployment, provisioning, runtime, manifests). Dissolves today's HostStage: the 4 `*Metadata` + synth/staging/sync are PREPARE's internal topics, in PREPARE's local `B`, invisible to the parent.
  - *PROVISION sub-pipeline* (local `B`): topics ensureProject → ensureNetworks → ensureProfile → ensureImage → imageStateConfigMap. Returns `ProvisionedResources`(providerContext, projectName, profileName, imageFingerprint, imageChecksum). **The former `BuildMetadata.update()` fan-in stays LOCAL to PROVISION** — `ensureImage` produces the image output in PROVISION's `B`; no mid-flow `update`, no leak to parent.
  - *LAUNCH*: createInstance, fan-in reading `PreparedHost` + `ProvisionedResources`. Returns `LaunchedInstance`(instance). OPEN: 1-topic sub-pipeline (uniformity) vs leaf execution topic in the parent (pragmatism) — lean leaf unless a 2nd gesture emerges.
  - *Parent Incus `B`:* 3 composite slots `PreparedHost` + `ProvisionedResources` + `LaunchedInstance`. `toResult` reads them for the final `BootstrapResult` (recombine into the result's metadata shape).
- [ ] **A2 — Materialize the sub-pipeline mechanism.** Confirm the port lets a `Topic<I,O>` body be a `during/then` chain sharing the parent `PipelineContext` with a strictly-local `B`. If a shared helper is needed to launch a sub-pipeline from a topic body, design it WITHOUT the `(R) this` trap. This is itself a port-contract question — settle it here (design-first) since it is the first sub-pipeline we build deliberately.
- [ ] **A3 — Build PREPARE, then PROVISION, then LAUNCH + parent.** Each sub-pipeline is a `State<I,O,B>` with its own `B`; transitions build each `I` from the local `B` + shared ambient. Delete `ApplyState`; the four `*Metadata` leave the registry (into PREPARE's/PROVISION's local `B`). Delete the `ensureImage` `registry.update(BuildMetadata)`.
- [ ] **A4 — Build green + `pulumi preview` green.** ERROR-clean NullAway. Proof the contract + sub-pipeline pattern hold on the richest pipeline. Commit.
- [ ] **A5 — Retro: stabilize the port contract (the decision point).** Settle what the conversion surfaced, BEFORE generalizing:
  - **`update()` verdict** — splitting the image output out of PROVISION should have removed the only `update` need. Confirm `PipelineContext` stays update-free.
  - **sub-pipeline contract** — is "shared ambient `PipelineContext` + local `B`" the right shape? Does launching a sub-pipeline from a topic body read cleanly? Should the sub-pipeline's composite output be a record the parent folds, or does the sub-pipeline write directly into the parent's `B` slot?
  - LAUNCH: leaf vs 1-topic sub-pipeline — decide by the code.
  - Design-first reopen of the grammar doc if the shape moved.

## Phase G — generalize (align the remaining pipelines)

Each = design records → convert → build+preview green → commit. Drop the inner `Pipeline`-prefixed names as each is touched. Once Incus is converted, **delete `controlplane.incus.ContextRegistry`** — the port `PipelineContext` supersedes it (no legacy variant, CLAUDE.md uniformity).

- [ ] **G1 — `ClusterSeedPipeline`** (5 topics, FAN-IN at ResourcesTopic). `PipelineInputs`→`Inputs` (naming rule), `StateBuilder` stays. Ambient (pulumiMode, bbox, resourceManager, outputBuilder) → `PipelineContext`.
- [ ] **G2 — `ApplicationPipeline`** (3 topics, linear — the original study case). Straightforward after the rich pilot: `EnvironmentOutputs`→`ClusterSeedInputs`→outputs, ambient (pulumiMode/bbox/resources/outputBuilder) in `PipelineContext`.
- [ ] **G3 — `DefaultManifestSynthesisService`** (proto-State: named outputs Scaffold/Registry/Targets + fan-in at SystemdUnitsStage). Migrate named-output fields to typed `O` records; retire the thread-local `ManifestSynthesisContext` into `PipelineContext`.
- [ ] **G4 — `SystemdInfrastructureSynthesizer` retrofit** (decided IN, § design). Output records `ToolsOutput`(nixInstall, floxInstall), `Rke2InstallOutput`(bootstrapEnv, install); input records incl. `StorageInput(ToolsOutput, Rke2InstallOutput)` (fan-in); each topic gains `toOutput()`, public getters DELETED; ambient (`SystemdChart`, `SystemdSynthesisContext`) → `PipelineContext`. Real chantier, not mechanical.
- [ ] **G5 — `FrameworkLaunchPipeline` 3-topic materialization** (decided IN, § design). Materialize discovery→plan→launch as a real type-state entry (deliver the promised inspectable `PlanDone`); `embedded()` stays the preset on top, javadoc corrected (it chains, does not expose states).
- [ ] **G6 — `TargetChecksumPipeline`** (applies-with-reserve, single `targetChecksums` accumulator). Judge at this point: full `State<I,O,B>` or keep the single-accumulator shape with just `PipelineContext` for ambient. Records may be oversized for one output — decide by the code, not dogma.

> `ResourceCreationPipeline` is a plain staged builder (no `during/then`), OUT of the type-state pattern. Leave it; it already uses the null-safe `@MonotonicNonNull`+`requireNonNull` builder convention.

## Phase F — follow-ups (NOT this wave)

- [ ] **F1 — Project-wide inner-prefix rename** (adopt the naming rule everywhere). Non-pipeline violations found in the scan: `Rke2labConfig.{Cluster,Node,Profile,Kubeconfig,Policy}Config`→drop `Config`; `DeploymentMetadata.GitMetadata`→`Git`; `ResourceManager.ResourceCreationResult`→`CreationResult`. Mechanical, separate blast-radius — do NOT braid into the pipeline wave. Its own commit(s).
- [ ] **F2 — Reconcile with OSGi-ownership S3.** When S3 (decision-into-OSGi) runs, it injects run data — ensure it consumes THIS plan's `PipelineContext`, not a new `TopicContext`. Cross-check the two plans at that point.

## Definition of done

- `Topic<I,O>` + `PipelineContext` live in `pipeline-port`, ERROR-clean.
- All type-state pipelines converted to `State<I,O,B>` + `PipelineContext`; flat `PipelineState`/`PipelineInputs` and the three ad-hoc ambient contexts (`ContextRegistry`, `ManifestSynthesisContext`, `SystemdSynthesisContext`) deleted — no legacy variant.
- The determinism rule holds: no `Topic.run` takes the context; every context read/write is inside a transition.
- Full `-Pall-worlds` build green, `pulumi preview` green, 0 NullAway.
- Atlas already aligned (done); update only if a retro (A5/G*) moves the shape.
