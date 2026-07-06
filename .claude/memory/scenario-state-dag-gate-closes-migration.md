---
name: scenario-state-dag-gate-closes-migration
description: "The 6th staging gate — SCENARIO_STATE_DAG, the build-time topological check on @Provided/@ExpectedScenarioState order — is the CLOSING task of the ClusterSeed BDD migration, not a prerequisite. It's the only spike mechanism never productionised; the ASM engine + @GovernedBy ritual already exist (5 gates shipped), so it's a 6th instance of an existing mould, and it only has something to check once the phases are real @ScenarioStage with those annotations."
metadata:
  type: project
---

**What.** The build-time gate that walks a composing scenario's phase-call order and rejects a phase
whose `@ExpectedScenarioState` type is not yet produced by an upstream `@ProvidedScenarioState` — the
topological safety the old `State<I,B>` type-state chain gave for free at compile time. It is the ONLY
one of the spike's 7 mechanisms never productionised ([[bdd-pipeline-migration-plan]]); the spike
holds only the ALGORITHM SKETCH (`DagGateSpikeTest` at tag `spike/bdd-pipeline-poc`, described in
`docs/architecture/osgi/bdd-pipeline-poc-design.adoc` learnings E1–E10).

**Why it's the CLOSING task, not a prerequisite.**
- It has nothing to check until ClusterSeed's phases are real `@ScenarioStage` classes carrying
  `@Provided/@ExpectedScenarioState` fields — i.e. until the transposition is DONE.
- The engine already exists: `StagingGate` enum has 5 gates (`RECORD_PURITY`, `SPEC_COVERAGE`,
  `INSTANCE_DISCIPLINE`, `REALM_BOUNDARY`, `SCHEMA_CONCORD`), all ASM-read from bytecode, all governed
  by `@GovernedBy(Gate, EnforcementLevel)`. Adding the 6th = a `ScenarioStateDag.java` beside the
  others + one enum constant + a `@GovernedBy` — the same mould as `RealmBoundary`/`SchemaConcord`,
  NOT a from-scratch chantier. (User's own recall confirmed: the governance framework is already in
  the extension — this gate is just a new instance of it.)

**The ritual (proven on REALM_BOUNDARY):** born WARN during the transposition (worklist visible) →
flip WARN→ERROR once the real phases are in place and the build is green at that level. That flip IS
the final safety lock of the migration — the last task of the ClusterSeed plan.

**Spike disposition (settled 2026-07-05):** nothing left to harvest from the spike NOW. Its mechanics
are superseded by the socle (`JUnitLauncherCore`, `OsgiConnection`, `LaunchedPipelineExchange`,
`@SeedRuntime`/`@IsolatedWorld`) AND by live seed-master prod (`DeferringScenarioExecutor` supersedes
PreviewExecutor — cleaner, inherits protected `methodInterceptor`, no reflection; the adaptive poll IS
`SeedSystemdAdapterEndpointGate`, which the spike copied FROM). The bench module
`osgi/runtime/bench/bench-bdd-pipeline` is ALREADY absent from `design/pre-integration` (never
integrated; lives only at the tag). No deletion gesture needed — it's already "in history."

See [[bdd-pipeline-migration-plan]] [[pattern-gate-coverage-map]] [[build-gates-over-review-reminders]]
[[spike-bdd-pipeline-reference-tag]].
