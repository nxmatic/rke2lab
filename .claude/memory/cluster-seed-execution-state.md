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

**DONE — RunMode rework (`208b3e0d`):** tri-state enum in `pulumi-edge`, projections `playsLive()`/
`materialises()`, `LiveGate.forRun(RunMode)`, `HostFacts` carries LiveGate not RunMode. Green.

**IN FLIGHT — Task 3 (ClusterSeedScenario skeleton), reworked live with the user (NOT the bâclé version
on disk).** The disk skeleton (untracked ClusterSeedScenario.java) used a `static volatile ReportModel
lastRunbook` + dead `import Nullable` — the user rejected it: "un null ne circule jamais nu; trouve la
source à la FRONTIÈRE, décore en Optional, remonte au traitant qui SAIT". Verified at bytecode:
`Scenario.getModel()`→`ScenarioModelBuilder.reportModel` is NOT ctor-initialized (can be null before 1st
setModel). jGiven stores its model in ITS ExtensionContext store (ns `com.tngtech.jgiven`, key
`report-model`) — NOT reachable from the launcher-level harvest. So harvest CANNOT read jGiven's store.
DECISION **P** (user: "on est solide"): add the session store as a 3rd param to `HarvestStrategy.harvest(
launcher, request, sessionStore)`; a small `afterAll` extension of OURS pushes `getScenario().getModel()`
(decorated `Optional.ofNullable` at capture) into OUR session store under an SSOT key `RUN_MODEL`; the
harvest reads OUR key, `orElseThrow` (the harvest is the traitant that KNOWS a run played). Kills the
static + the home-made null + the "removed in Task 8" debt. `@MonotonicNonNull` (checkerframework, 80
uses in repo) is THE annotation for genuinely-deferred fields (the stages will need it) — but P avoids a
null field here entirely so it's not needed. Touches socle (HarvestStrategy iface +
InContainerJUnitRunner harvest + 3 scenario-engine tests add the ignored 3rd param). Whiteboard section
"HARVEST DU REPORTMODEL". Coding P by hand (too subtle for a blind subagent).

**DONE — Task 3 (`5ac7cf0d`):** ClusterSeedRun + ClusterSeedScenario skeleton + HostSeeder (renamed from
HostFactsSeeder — seeds the fact bag AND the ReportModel). Inject-the-model: driver creates the
ReportModel, HostSeeder plants it in jGiven's OWN store (ns `Stage.class.getPackageName()`, key
`report-model`) BEFORE jGiven's postProcessTestInstance (which would overwrite via its store — bytecode
verified); jGiven writes the run into it; driver renders from its held reference. No static, no null, no
harvest-back. HarvestStrategy stays `(launcher, request)`.

**IN FLIGHT — Task 4 (attached-framework seam + 3 pure phases), design reworked live, DECIDED F1.**
Socle done: `BootedFramework.attached(fw)` (flag `owns`, close no-op) + `OsgiConnection.framework()`
(cast getBundle(0), NOT adapt — runtime identity). HostSeeder gained `CONNECTION` key + `ConnectionAware`.
seed-master pom: scenario-engine promoted test→compile (HostSeeder/stages in src/main use OsgiConnection).
KEY DESIGN (F1, user-confirmed): the plan's PureStagesTest seeds standalone and would make the 3 phases
TOUCH THE REAL WORLD (Preflight reads real git/flake; Bbox reads real bbox secrets; Incus provisions +
crashes on Deployment.getInstance() off-Pulumi, line 584 ungated). Two axes were conflated: LiveGate =
PROD (live vs deferred in real preview); probes/fakes = TEST (inert without touching reality). The
established pattern IS probes: `SystemdAdapterProbe` is a `@FunctionalInterface` (live impl + fake in
test). So — for UNIFORMITY — extract the same for the pure phases: `PreflightProbe`/`IncusProbe` +
make Bbox injectable, each `@FunctionalInterface` with a live impl (real collaborator) and a test fake.
Collaborators today are static/final (EntryGatePolicyEnforcer static; IncusResourceBootstrap final;
BboxReconciliationOrchestrator final) → must extract interfaces. IncusProbe returns
`Optional<BootstrapResult>` (an Outcome — present iff mutation ran; BootstrapResult has NO cheap deferred
form, 10 fields/5 composite — do NOT fabricate one). Stages take injected probes; test injects fakes;
LiveGate keeps its PROD role. NEXT: read FakeSystemdAdapterProbes + how SystemdAdapterScenario receives
its probe (probed_by) + NestedRunbookTest injection path, mirror it. Whiteboard §TASK 4.

**jGiven config friction (2026-07-06, will recur for the 6 other pipelines).** jGiven's `Config` is a
STATIC singleton (private ctor) whose setters (`setReportEnabled`, `setReportDir`) just write
system-properties; the junit5 extension reads it at write-time (`afterAll` → `new CommonReportHelper()
.finishReport(model)`), never receives a per-run config. jGiven assumes one-process = one-config (the
surefire case). We run scenarios as a RUNTIME ENGINE (many runs, one JVM) — an unforeseen use — so a
launcher run would dump a stray `./jgiven-reports/null.json` (null because our injected model has no
name until we copy jGiven's identity — see below). Fix in `HostSeeder`: (1) copy name/className/
description from jGiven's beforeAll model onto ours before planting it (kills null.json + "Test Class:
null"); (2) `Config.config().setReportEnabled(false)` in postProcessTestInstance + RESTORE the prior
property in `afterAll` (HostSeeder declared first → its afterAll runs LAST, after jGiven's report write).
Save/restore leaves no leaked global state. Cannot contribute a scoped config (singleton closed); a real
fix would be upstream. This is the dogfooding tax the CLAUDE.md's "BDD-as-engine" predicts.

**IN FLIGHT — Task 5 (SystemdAdapterStage, island 1), design SETTLED after a long joint exploration.**
Committed nothing yet. Written & compiling: `OsgiConnection.awaitService(Class,long)` (default method,
lookup via context() — NO static, NO delegation to BootedFramework); `SystemdAdapterStage` (transposes
SystemdAdapterTopic.launch: play SystemdAdapterScenario nested → success provides `adapterLaunch`
Map@Resolution.NAME; failure → consultDoctor + ReadinessAuthority verdict → STOP throws / CONTINUE sets
degraded); composed in ClusterSeedScenario (`.and().systemdAdapter()`). Executor decision: A —
PendingMarkingScenarioExecutor DEFERRED to Task 7 (executingLive is preview-only, no live client now).

KEY DESIGN CHAIN (user's questions drove it, each verified at source — all REVERSIBLE decisions we
landed):
1. Services (SystemdRuntimeProbe/ReadinessAuthority/ConsultingService) resolve via `connection.
   awaitService(X)` INSIDE the stage — NOT injected by the driver. Why: the launcher is HOST-AGNOSTIC
   (can run host-side OR in-container via BundleReference/wiringOf). If the driver injected resolved
   instances, an in-container run would depend on a host resolution → breaks agnosticism. The
   connection knows which world it's in; the stage just asks it. (This REVERSED an earlier "driver
   resolves, injects instances" idea — U1-a — which I dropped.)
2. Can't use DS (@Reference) on a stage: jGiven instantiates stages by reflection (ScenarioExecutor.
   addStage → newInstance), so SCR has no hook — true even in-container. seed-master is HOST anyway
   (no @Component, doesn't compile against DS). awaitService IS the host↔OSGi seam (the other half of
   DS: DS publishes in the registry, the host reads it). Precedent: LiveClusterReadinessProbe receives
   ClusterReadinessContact resolved by the driver's awaitService.
3. The probe axis vs the service axis are ORTHOGONAL: the endpoint SystemdAdapterProbe (dbus reachable)
   stays injectable (F1, live/fake — fake exists); the domain SERVICES come from awaitService. Two
   natures, two channels.
4. TEST STRATEGY (user's best insight): NOT a fake connection with hand-made fake services (fiction,
   like the rejected BootstrapResult.deferredPreview). Instead a **TEST FRAGMENT** contributing fake
   `@Component`s (ReadinessAuthority/SystemdRuntimeProbe/ConsultingService) attached to the scenario's
   host bundle → SCR wires them (DS IS the mechanism — user's original unease satisfied) → awaitService
   finds them → agnostic, real registry, test-controlled, LIGHT Felix. Proven pattern in repo:
   `osgi/runtime/bench/bench-fragment-contribution` (FragmentContributedComponent = a @Component in a
   FRAGMENT, awaitService finds it). Copy that template (Fragment-Host bnd + fake @Components). This
   fragment RESERVES for Task 6 too (cluster-readiness resolves services the same way).
5. Verdict decision (STOP→throw / CONTINUE→degraded) stays UNIT-testable without Felix (as
   SystemdAdapterVerdictTest does today via a fake ReadinessAuthority). Two test levels: unit verdict +
   in-container fragment integration.

TODO Task 5 (resume): (a) write the test fragment module (bnd Fragment-Host + fake @Components, template
= bench-fragment-contribution); (b) SystemdAdapterStageTest boots light Felix + fragment, seeds
connection, asserts SUCCESS + "systemd adapter" narrated; (c) migrate SystemdAdapterVerdictTest +
RunbookRenderingTest off the topic; (d) build green; (e) commit. Whiteboard has the forks. See
[[jgiven-custom-executor-seam]].

**Task-5 sub-decisions landed (2026-07-06), all verified at source:**
- Fake fragments SHIPPED (compile+package green, descriptors generated): `dbus-systemd-edge-fake`
  (FakeSystemdRuntimeProbe) + `doctor-core-fake` (FakeReadinessAuthority + FakeConsultingService),
  named by HOST (generic reuse). Full design in [[osgi-connection-service-selector]].
- DEFAULT-SAFETY: every fake carries `variant=fake` + `service.ranking:Integer=-1000` so a nude
  `awaitService(Class)` NEVER resolves a fake (prod also excludes type=fixture from staging).
- TWO probe AXES both alive (NOT redundant): axis-1 injected app probe (SeedProbes: pure phases,
  offline) vs axis-2 OSGi registry service (systemd dbus, cluster kubectl). Asymmetry ASSUMED.
- INCUS DEBT: `SeedSystemdAdapterEndpointGate.waitForInstanceReachable` still runs `incus exec`
  host-side (the incus external edge is NOT built — external-edges chantier: incus/cluster/host-fs
  remain; only pulumi/ssh-to-age/dbus-systemd shipped). So the happy-path CANNOT play via liveProbe()
  in test (would hit real incus). Marked `@Transitional` (annotation EXTENDED: `spec()` now optional
  `default ""` for spec-less code-awaiting-a-chantier debt; gate reads presence only, verified).
- TWO-TEST structure (user chose, then axis-1 reopened "vrai Felix"): (1) happy-path OFFLINE — the
  stage plays an INJECTED reachable SystemdAdapterProbe (mirrors the old topic's simulated/liveProbe
  seam, NOT SeedProbes), 4 phases, no Felix, restores Task-4's offline property; (2)
  SystemdAdapterStageTest — REAL Felix + both fragments attached: injected FAILING probe → onFailure →
  awaitService(ReadinessAuthority) resolves the fake doctor via `(variant=fake)` → CONTINUE_DEGRADED,
  plus a direct awaitService(SystemdRuntimeProbe, selector) proving the dbus fragment.
- NEXT in-flight: SystemdAdapterStage must accept an OPTIONAL injected SystemdAdapterProbe (present →
  play it; absent → liveProbe() via awaitService). Then write the 2 tests, migrate
  SystemdAdapterVerdictTest + RunbookRenderingTest off the topic, build green, commit.

**Task 5 COMMITTED `3869cdd64` (2026-07-07), full build green (spec-coverage 0/0):**
- `PureStagesTest` — full 4-phase scenario plays OFFLINE (inert probes + injected reachable systemd
  probe via `HostSeeder.SYSTEMD_PROBE`); roots `[preflight,bbox,incus,systemd adapter]`.
- `SystemdAdapterVerdictTest` — a failed probe renders FAILED for BOTH verdicts (fidelity); the
  verdict is propagation (STOP throws `SeedAborted`) / degraded observation (CONTINUE), NOT status.
  Uses `StubConnection` (serves fakes by type, no Felix).
- `SystemdAdapterStageTest` — REAL Felix + both fake fragments attached to their hosts; the
  `(variant=fake)` selector resolves the fake SystemdRuntimeProbe + ReadinessAuthority from the
  registry; the negative service.ranking guard asserted on the ServiceReference.
- `TopicFailure` → `SeedAborted` (new bdd-world type; Topic vocabulary banned in bdd/ EXCEPT the
  driver `ClusterSeedTopic`, name kept). "transposes XxxTopic" comments stripped from bdd/.
- Old `SystemdAdapterVerdictTest` + `SystemdAdapterTopicFixture` (pipeline/stages/) DELETED.
- `RunbookRenderingTest` REVERTED (it never depended on the topic — only a comment; it tests
  `RunbookRenderer` via `Scenario.create(SystemdAdapterScenario)`, the right isolate). Plan drift:
  5h "migrate RunbookRenderingTest off the topic" was WRONG — nothing to migrate.

**DEBT for Task 7 (renderer simplifies with the composite migration, NOT a standalone refactor):**
`RunbookRenderer.normalize()` is N-scenario dead weight — it names the model because checkpoints play
`Scenario.create()` standalone (no className); the composite scenario is played by the launcher via
`ClusterSeedScenario` (a real named class) → jGiven fills className natively → `normalize()` GOES.
`injectDiagnosis()`/`scenarioFor()` join the doctor diagnosis to a top-level scenario BY TITLE
(`Checkpoint.scenarioTitle().equals(scenario.getDescription())`); the composite has ONE scenario with
systemd as a NESTED STEP, so the injection must retarget scenario→nested-step. Both are Task 7 (rendu
composite), user agreed to defer. The JSON→AsciiDoc pipeline + best-effort try/catch are essential
(jGiven requires the intermediate JSON dir), keep them.

**Task 6 IN FLIGHT (2026-07-07):**
- `ClusterReadinessStage` written+compiles: plays readiness phases WITHOUT the nested systemd replay
  (the composite plays systemd as a top-level phase → dependency is the top-level order + consumed
  `adapterLaunch` state, explicit not narrated). The reused `ClusterReadinessScenario` KEEPS its
  `the_systemd_adapter_dependency_is_satisfied()` replay method (marked `@Transitional`) for the
  still-live isolated `ClusterReadinessTopic` — the stage just doesn't call it.
- `@Transitional` EXTENDED to `@Target({TYPE, METHOD})` (spec-less form): marks a method of the old
  model kept alive only for a condemned caller.
- RESOURCE PIPELINE made PURE (no duplication — "mieux que la duplication", user's words):
  `ResourceCreationPipeline` now RECEIVES a `VerificationResult readiness` (required, non-null) and
  never plays readiness. Lost doctor/clusterReadinessContact/readinessEnabled/runbook (all eager-play
  only). The eager-play REMOVED from the pipeline and INLINED into the fluent
  `ResourceManager.createResources` (`@Transitional`, builds ClusterReadinessTopic locally — doctor/
  contact stay local, never enter the pure pipeline). New `createResources(…, VerificationResult)`
  calls the pure pipeline directly. ZERO null, ZERO Optional-of-service. Compiles green.
- DERIVE-GUARD (user corrected me twice): do NOT make one pipeline serve both fluent+composite via
  null/Optional-of-service — that was the drift. One pure pipeline, the eager-play lives with the
  condemned fluent caller.

**TARGET VISION (user, 2026-07-07): the `bdd/` package is TRANSITIONAL.** It coexists with the old
world (`pipeline/`, `pipeline/stages/`, `resources/` eager). When ALL 7 pipelines are pure-BDD
(Task 8+ finishes the migration), `bdd/` has no reason to exist: the stages/scenarios move to their
definitive home and the old world (Topic, fluent runner, eager ResourceCreationPipeline) is deleted
in one block. "on ajoute le neuf, on marque l'ancien @Transitional, on coupe en bloc à la fin."

**Task 6 DONE (2026-07-07), full build green (spec-coverage 0/0), ready to commit:** ClusterReadinessStage
+ ResourcesStage (fan-in, nests cluster-readiness) written+compile; composed in ClusterSeedScenario
(5 phases: preflight/bbox/incus/systemd/resources). Pure pipeline (no null/Optional-of-service). New
`HostSeeder.CLUSTER_PROBE` channel + `ClusterProbeAware` (mirror of SYSTEMD_PROBE). HostFacts gained
`materialises` (RunMode's 2nd projection, resource-path face). PureStagesTest DELETED (offline play of
the full scenario is scaffolding that never runs in the target — resources/cluster are axis-2, they
dialogue with the world) + `FakeSeedProbes.reachableSystemdAdapter` dead-removed. Fixed a PRE-EXISTING
socle regression [[boot-discovery-mandatory-import-incontainer]].

**Task 6 DEFERRED (plan drift, tracked):**
- `ResourcesStageTest` (full 5-phase DAG in real Felix) NOT written: `ResourcesStage` needs a WHOLE
  `BootstrapResult` (10 Pulumi composites, no test form — the recurring wall since Task 4). Fabricating
  one is forbidden (memory). Deferred to Task 7, where a real end-to-end BootstrapResult + composite
  rendering are needed anyway. The 5-phase DAG composition is thus NOT yet asserted by a test.
- `NestedRunbookTest` NOT migrated to drive the stage (plan said to): it drives the still-live
  condemned `ClusterReadinessTopic`, stays green, no real dependency to migrate now — migrates when
  the topic dies (Task 8), like RunbookRenderingTest in Task 5.

**THEN:** Tasks 6-9. See [[runmode-livegate-pulumi-abstraction]].

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
