---
name: cluster-seed-execution-state
description: "Precise resume point for the ClusterSeed BDD migration EXECUTION (2026-07-06, mid-flight, pre-compaction). The task-by-task plan is at docs/superpowers/plans/2026-07-05-cluster-seed-scenario-plan.md (gitignored). Tasks 1-2 committed; a socle ClassRealm chantier is in progress (interrupted the migration); RunMode must be reworked. Whiteboard .claude/claude-preview.adoc holds all design figures (also gitignored — survives compaction)."
metadata:
  type: project
---

**The plan** (9 tasks, TDD, no-placeholder): `docs/superpowers/plans/2026-07-05-cluster-seed-scenario-plan.md`
(gitignored). Whiteboard with every design figure: `.claude/claude-preview.adoc` (gitignored, on disk —
read it first on resume). Execution is subagent-driven (one general-purpose subagent per task, I review
between). Drift rule: any subagent-reported drift → joint review with the user, decide case-by-case.

**Committed so far:**
- `c901949` Task 1 — JUnitLauncherCore opens a LauncherSession (5-arg run, seedable session store).
- `4b40a9d` Task 2 — RunMode + HostFacts + HostFactsSeeder. NOTE: RunMode here is the OLD 2-boolean form,
  MUST be reworked (see [[runmode-livegate-pulumi-abstraction]]).
- `fa4ff10` design memories.
- `00c2441` socle ClassRealm step 1+2 (interface + HostClassRealm rename). See
  [[classrealm-adaptable-pattern]].

**DONE — socle ClassRealm chantier CLOSED (2026-07-06).** `00c2441` (interface + HostClassRealm rename)
then `8ccc8ed` (BundleClassRealm + `ClassRealm.of(loader)` + wiringOf collapsed + 2 tests). 6/6 green.
Design settled with the user: per-bundle IS a realm (legitimized BundleClassRealm); TWO OSGi mechanisms
kept distinct (A adapt=bounded realm, B service-registry=future ServiceBroker, NOT fused); step 5 =
migrate NOTHING by force (self-cast group stays native — `of` pays only where a RAW loader must decide the
world). Full reasoning in [[classrealm-adaptable-pattern]].

**NEXT — resume ClusterSeed at: rework `RunMode`** → tri-state enum at the edge + `LiveGate.forRun(RunMode)`
projection; phases consume `LiveGate` not RunMode ([[runmode-livegate-pulumi-abstraction]]).

**THEN resume ClusterSeed Tasks 3-9:** 3=ClusterSeedRun+scenario skeleton; 4=attached-framework seam
(OsgiConnection.framework() + BootedFramework.attached()) + Preflight/Bbox/Incus stages; 5=SystemdAdapterStage
(island1); 6=ResourcesStage+nested ClusterReadinessStage (island2); 7=preview decorate+OutputsStage;
8=rewrite ClusterSeedTopic driver + delete fluent pipeline+6 topics; 9=SCENARIO_STATE_DAG gate WARN→ERROR.

**Known drifts to fold (from Task 2 subagent):** `OperatorConfiguration.asRke2labConfig()` → real is
`asDto()`; seed-master needs junit-jupiter-api at compile scope (added); pre-existing doctor-port
spec-coverage gate trips full `package` (read surefire Tests-run line, not final BUILD FAILURE); build
command for downstream reactor: `./mvnw clean package -pl :seed-master -am -Dmaven.build.cache.skipCache=true
-DskipTests=false -Drke2lab.staging.skip=true -Dtest=<T>` (plain `test` fails downstream consumers).
DiscoverySelectors import = `org.junit.platform.engine.discovery.DiscoverySelectors` (fixed in plan).

**Naming locked:** ClusterSeedScenario, *Stage (not *Phase), PendingMarkingScenarioExecutor, ClusterSeedRun
(local), ClusterSeedTopic = the call-site kept. See [[bdd-pipeline-migration-plan]]
[[cluster-seed-inbound-session-store]] [[jgiven-custom-executor-seam]] [[collaborative-design-method]].
