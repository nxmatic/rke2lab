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

**THEN:** Tasks 5-9. See [[runmode-livegate-pulumi-abstraction]].

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
