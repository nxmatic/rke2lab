---
name: runbook-doctor-state
description: feature/runbook-doctor — Increments A-D DONE; BDD-quality pass COMPLETE (explicit steps, probe-injection dedup, POM restructure, nesting, BDD unit tests all committed); only remaining future work = the deferred shared report-node DAG model (its own chantier)
metadata: 
  node_type: memory
  type: project
  originSessionId: fecaba54-8122-4dc1-8916-743ef5d2dec0
---

Active chantier on branch **`feature/runbook-doctor`**: build the runbook + doctor subsystem.
Design DONE; **A DONE** (3b46b249), **B/doctor DONE** (aa55ced4), **C/checkpoint#2-nested DONE
DSL-first** (9685793c), **D/live-cluster-wiring DONE** (fc91f127), **D-preview-fix DONE** (140414cb,
2026-06-06). **NEXT = the last deferral**: the shared report-node model so the plan renders into each
runbook node's Diagnosis/Mitigation sections (vs. today's inline log) and records explicit
`dependsOn` DAG edges (today the edge is shown via @NestedSteps nesting). Now unblocked: both
checkpoints are live BDD scenarios.

**Both checkpoints now render in the runbook on BOTH `pulumi preview` AND `pulumi up`.** The preview
fix (140414cb): ClusterReadinessStage was early-returning in preview (cluster absent from the
preview runbook); now it sets jgiven.report.dry-run (bodies skipped, no live infra) but still
plays+finish()es the scenario so the shell renders, then sinks deferredPreview. Verify after running
pulumi: runbook at `seed-master/target/runbook/adoc/index.asciidoc` should show "Systemd adapter
becomes reachable" AND "Cluster becomes ready" (with the nested systemd-adapter dependency).

**BDD-QUALITY PASS (2026-06-07, IN PROGRESS) — triggered by the user reading the rendered runbook.**
Four commits landed on `feature/runbook-doctor`, all 31/31 green; two chantiers remain in the pass.

*1. Cluster naming + visible phases (34f2a1a9, then refined by 0b3eb45d).* The runbook showed
`Given the cluster "master"` (it was passed `config.nodeName()`; fixed to `config.clusterName()` →
"bioskop") and one opaque `the readiness phases run` line. **Final shape (0b3eb45d):** the three
phases are EXPLICIT FLUENT STEPS chained in canonical order —
`.the_kubeconfig_is_published().and().the_api_is_ready().and().the_required_controllers_are_effective()`
— each delegating to a private `checking(ClusterReadinessPhase)` (the enum stays the single join to
probe + simulation; method name is the narration, enum label is the short dossier tag). Replaced an
intermediate `@NestedSteps`+loop+`break` attempt. **JGIVEN SEMANTICS (settled by a scratch probe):**
a top-level fluent chain `a().and().b().and().c()` where `b` throws → JGiven SKIPS the bodies of the
downstream chained steps and marks them SKIPPED (fail-fast is the chain's own semantics, no manual
break). Contrast: inside `@NestedSteps` the exception is DEFERRED (re-thrown at finished()), so a
loop there would NOT fail-fast. `ThenClusterReadiness` is now a thin closing assertion (reached only
on full success); `failingPhase`/`failingDossier` deleted. NestedRunbookTest asserts per-step
StepStatus PASSED/FAILED/SKIPPED — rigorous proof the downstream body never ran.

*2. POM restructure (8ed3c513) — the user's call: BOM has its own lifecycle/version; children carry
NO dependencyManagement.* `bom` is now STANDALONE (no `<parent>`, own version `1.0.0-SNAPSHOT`, only
EXTERNAL version management — the rke2lab inter-module entries moved out). The PARENT holds the
single `<dependencyManagement>` (imports the BOM + manages rke2lab modules at `${project.version}`)
and a common test toolchain in `<dependencies>`: junit-jupiter + jgiven-junit5 + mockito-core +
mockito-junit-jupiter (all test scope, versions from the BOM). The 6 children dropped their
dependencyManagement and inherited test deps; seed-master keeps `jgiven-core` in MAIN scope (plays
scenarios in prod). Mockito 5.11.0 + byte-buddy 1.14.19 come from spring-boot-dependencies, aligned
with JGiven's byte-buddy. Verified two ways: full `clean install` AND from-source `package -pl
:seed-master -am` (the parent importing a reactor BOM resolves without install — no cycle, because
the BOM has no parent).

*3. Test-double policy (decided) + scenario dedup (0d2c7a4f).* **Policy:** Mockito IS in the project
now; rule = lambda for a single-method @FunctionalInterface with a canned return (the probes);
Mockito when verifying an interaction or doubling a multi-method collaborator (the doctor) or a
STATIC seam (`mockStatic(Deployment.class)` for the preview path); NEVER mock a JGiven Stage
(byte-buddy already subclasses them). The user also wants BDD (Given/When/Then) for UNIT tests too,
accepting two JGiven uses: main (scenarios ARE prod behaviour) + test (readable component tests).
**Dedup:** `NestedRunbookTest` used to re-implement the cluster Given/When/Then by hand → the
scenario lived in TWO places. Fixed by INJECTING the probe: `ClusterReadinessStage` and
`SystemdAdapterStage` now take their probe as a ctor param (prod injects ProductionClusterReadinessProbe
/ the dbus gate lambda; SystemdAdapter's preview-only simulate override still wins). The test now
calls the REAL `new ClusterReadinessStage(...).launch()` with a fake/simulated probe + captured sink
— same code prod runs — and also asserts the VerificationResult projection + that the stage consults
the doctor (logged ⚕/℞). `pulumiMode=false` short-circuits `Deployment.getInstance()` so the stage
runs offline. **GOTCHA:** `Deployment.getInstance()` returns `DeploymentInstance` (not `Deployment`)
— mock that type. **KNOWN DEBT:** Mockito's inline mock-maker self-attaches the byte-buddy agent →
"Java agent loaded dynamically" WARNING (non-blocking on JDK 25, forbidden in a future JDK). A
`-javaagent:${net.bytebuddy:byte-buddy-agent:jar}` argLine attempt FAILED (the dependency-plugin
`properties` goal did not substitute the path → JVM got the literal string, crashed the fork);
reverted entirely (no trace). Revisit with a tested approach when the JDK enforces it.

*4. Nesting (e3ac4b4d) — DONE.* The six top-level stage files (Given/When/Then × SystemdAdapter +
ClusterReadiness) collapsed into TWO per-scenario containers `SystemdAdapterScenario` /
`ClusterReadinessScenario`, each a `final` class with a private ctor holding `public static class
Given/When/Then extends Stage<…>`. bdd/ top-level count 23→19. Cross-scenario edge preserved:
`ClusterReadinessScenario.When` references `SystemdAdapterScenario.Given/When/Then` via
`@ScenarioStage` (the nested dependency). Consumers rewired: both prod stages' `Scenario.create`,
`SystemdAdapterScenarioTest extends ScenarioTest<Scenario.Given,…>`, `RunbookRenderingTest`.
**Rendered prose is byte-identical** — JGiven derives step names from METHOD names + scenario titles
from `startScenario(...)` strings, neither touched; only Java type names changed. Proven green by
NestedRunbookTest asserting exact step text. 31/31.

*5. BDD unit tests — DONE (the LAST pass item, two commits).* **Commit 97d0b9d0 — shared fixture
DSL.** The same `Map.of("incus"…"image"…"worktree"…) → ConfigLoader → from(dto)` block was
copy-pasted across 6 test files (as `config()/policy()/dto()/loaderOf()/full()/mandatoryOnly()`).
Extracted to ONE `src/test` DSL `controlplane/config/OperatorConfiguration`:
`empty()/mandatory()/full()` + fluent `with(section,key,val)`/`without("incus.configDir")` +
`asLoader/asDto/asBootstrapConfig/asPolicy`. All 7 tests rewired; mechanics tests (ConfigLoader,
Rke2labConfig, BootstrapConfigFrom, ClusterReadinessProjection) KEEP exhaustive assert bodies — only
input construction goes through the DSL (TDD/BDD split preserved). `without()` expresses
missing-mandatory cases as `full().without(key)`. **DELEGATION RULE (user's, 2026-06-07):** when a
test's SUBJECT is config behaviour (ready-vs-missing), delegate to the `ConfigEntryGate` BDD stages
(compose via `@ScenarioStage`, the follow-the-chain pattern); the DSL is for config-as-FIXTURE only.
**Commit 97383f0b — DoctorScenario.** `DoctorTest` mixed behaviour (consult→plan) + type mechanics
(symptom parse, dossier round-trip). Split: new TEST-ONLY `DoctorScenario` (Given a doctor staffed /
a failure presenting a symptom · When consulted · Then a prescription issued | no treatment but
plan names the symptom) + `DoctorScenarioTest`; Generalist/Specialist/Dossier vocab IS the DSL.
Stages live in `src/test` (doctor is consulted INSIDE checkpoints, never standalone in prod →
localisation rule). The 3 recognized-but-untreated cluster symptoms = one scenario each (NOT
`@ParameterizedTest`) — each renders its own runbook line AND avoids JGiven 2.0.3 probing the JUnit
≥5.13 `ParameterInfo` class (we resolve 5.10.5 via spring-boot) which logged a harmless
NoClassDefFoundError. 33/33 green.

**THE BDD-QUALITY PASS IS COMPLETE.** All 5 items done. Remaining future work is the DEFERRED shared
report-node DAG model (below) — its own chantier, not part of this pass.

**JUNIT 6 / JGIVEN WATCH-ITEM (investigated 2026-06-07, user asked "why not bump to 6.1.0?"):**
BLOCKED, not by caution — by the dependency graph. JUnit 6.0 REMOVED 5.x APIs JGiven 2.0.3 was
compiled against (`PreconditionViolationException`, `ReflectionSupport.loadClass()`, …) → runtime
`NoSuchMethodError`. JUnit + JGiven must move TOGETHER. **Latest RELEASED JGiven is 2.0.3** (Maven
Central confirmed); JUnit-6 support exists only on JGiven's unmerged/unreleased `main`. So no bump
until JGiven ships a JUnit-6 release. When it does: clean coordinated bump = import `junit-bom:6.x`
BEFORE spring-boot-dependencies in our standalone `bom/pom.xml` (first `import` wins) + bump
jgiven-* together. No functional gain today regardless.

**User's design seed for the deferred DAG model:** "it's the step/edge that decides fail-fast vs
fail-at-end." Refined together: a step isn't fail-fast in the absolute — it BLOCKS its dependents
when it's a precondition. Failure propagates fail-fast ALONG dependsOn edges (dependents skipped)
and fail-at-end BETWEEN independent branches (all run, all report). Today's 3 phases are a linear
chain so everything is fail-fast and correct; fail-at-end becomes meaningful only when the shared
report-node DAG lands AND there's a genuine pair of INDEPENDENT checks (none in cluster yet — its
phases are intrinsically sequential). Don't build per-step policy now (rule-of-three: one shape).

**PREREQUISITE BEFORE THE DAG — unify the Dossier/output shape (user-raised 2026-06-07, order
decided "shaping first, then DAG").** The two checkpoints DON'T share an output shape, and the DAG
nodes would inherit the fork (they'd special-case one vs the other). Diagnosis:
- *systemd-adapter:* one `Dossier` per checkpoint → `Dossier.toOutputMap()` (flat map) →
  `SystemdAdapterResource.asResourceOutputs` (`Output.of` per key). Clean, Dossier-native.
- *cluster:* the stage computes per-phase `Dossier`s, then THROWS them away into a hand-maintained
  `VerificationResult` (record at `ClusterBootstrapReadinessVerifier.java:441`, 8 fields:
  readinessEnabled/kubeconfigPublished/apiReady/controllersEffective/handoffReady/bootstrapStatus/
  summary/requiredControllerRefs; factories ready/skipped/deferredPreview/failed; `asOutputs()` :504
  emits 7 `cluster*` keys). Then `ReadinessOutputMapper.mapToOutputs` (dual-mode Output|plain) adds
  handoffReady/bootstrapStatus/nextStep; consumed at `OutputBuilder.java:85`. `ClusterReadinessResource`
  is a thin mirror holding `Output<VerificationResult>`. This is the LEGACY pre-Dossier path surviving
  inside an otherwise-BDD checkpoint — `ClusterReadinessStage.failedProjection()` rebuilds a
  VerificationResult from the phase Dossiers (lossy).
TWO shape problems: (1) **output-path divergence** — `toOutputMap()` vs `ReadinessOutputMapper`/
`VerificationResult` for the same concept; a checkpoint should have ONE way to become outputs. (2)
**Dossier granularity** — systemd = 1 Dossier/checkpoint; cluster = N phase-Dossiers with NO
first-class checkpoint-level Dossier aggregating them. The DAG wants exactly node=checkpoint=1
Dossier(+plan+edges). *Target:* cluster gains a checkpoint-level `Dossier` (aggregating its phases),
outputs via `toOutputMap()` like systemd; `VerificationResult`/`ReadinessOutputMapper` become a thin
view over it OR are deleted (no-compat: same change). **HARD GUARD:** the Stage-B handoff keys must
stay byte-identical — handoffReady→nextStep + the 7 `cluster*` keys + bootstrapStatus;
`ClusterReadinessProjectionTest` is the pin. THIS LANDS BEFORE THE DAG.

**DAG MODEL — START-HERE for next session (code anchors + the real architectural decision).**
*Where the plan lives TODAY (what the DAG replaces):* both checkpoint stages call a private
`consultDoctor(...)` that ONLY `log()`s the diagnosis inline — `ClusterReadinessStage.java:203` (sym
"⚕"/"℞" at :210/:212) and `SystemdAdapterStage.java:187` (:192/:194). The `RemediationPlan` is
computed, logged, and dropped; it never reaches the runbook node. *The goal:* that plan renders into
the node's own Diagnosis/Mitigation sections + the `dependsOn` edge becomes explicit DAG data (today
the edge is shown only via `@NestedSteps` nesting).
**THE DECISION the next session must make first (under-recorded until now):** the runbook is NOT a
free-form template — `RunbookRenderer` (bdd/RunbookRenderer.java) renders via JGiven's own
`AsciiDocReportGenerator` reading a JGiven `ReportModel`/`ScenarioModel`. So "add Diagnosis/Mitigation
to each node" forces a choice between THREE routes: (a) attach the plan into the JGiven model as
step `InfoTag`s / attachments / extended description so JGiven's AsciiDoc emits it (stays inside
JGiven, but JGiven controls layout); (b) STOP using `AsciiDocReportGenerator` and write our OWN
renderer over `ReportModel` so we own the node layout (Diagnosis/Mitigation/dependsOn sections) — more
work, full control; (c) a hybrid: keep JGiven for the scenario body, post-process/append our node
sections. Pick the route BEFORE coding — it's the fork the whole chantier hangs on. RunbookRendering
Test already asserts "no Diagnosis/Mitigation section before the doctor exists", so the test that
flips that assertion is the natural TDD entry point. PREREQUISITE still standing: a genuine pair of
INDEPENDENT checks must exist before fail-at-end is even exercisable (cluster's phases are a linear
chain) — so landing the node-render (plan→sections) is independently useful NOW, but the edge-policy
(fail-fast-along-edges vs fail-at-end-between-branches) stays dormant until a branching checkpoint
appears. Sequence suggestion: node-render first (useful immediately), edge-policy when rule-of-three hits.

**Verified earlier this session:** `.local.d/bioskop/master/host.preview` (synthesized config) is
COMPLETE vs the applied `host/` — 0 config files changed/added; the 105 "missing" files are all
runtime flox artifacts (.flox/log, .flox/cache) + cluster-api/staged/image-state-configmap.yaml
(written at pulumi-apply time by design, IncusResourceBootstrap.createImageStateConfigMap, the
chicken-and-egg fingerprint pattern — docs/staged-post-cluster-resources.adoc). So A–D changed only
the seed-master control-plane (runbook/doctor), NOT the synthesized host manifests — applying is
essentially a no-op on the host-state side.

**Build/test invariant (use this exact command — repo build-cache can give stale reactor jars):**
`flox activate -- ./mvnw package -Dmaven.build.cache.skipCache=false -pl :seed-master -am
-DskipTests=false`. 31/31 seed-master tests green. NEVER `mvn install` to ~/.m2 (CLAUDE.md).

**Increment D delivered** (seed-master): cluster-readiness now played LIVE as a BDD scenario (was
procedural verify() in a lazy Pulumi applyValue lambda → never hit the runbook). `ClusterReadinessStage`
(mirror of SystemdAdapterStage) plays it EAGER in the pipeline thread (deps already concrete), so it
records into the shared ReportModel before BootstrapStage's finally renders — avoids the applyValue
fires-after-render empty-node trap. `ProductionClusterReadinessProbe` bridges each phase to a public
per-phase check on the verifier (checkKubeconfigPublished/checkApiReady/checkControllersEffective; the
phase-0 bootstrap-preconditions gate folded into phase 1); the two dead verify() overloads deleted.
New typed Symptoms KUBECONFIG_MISSING/API_NOT_READY/CONTROLLER_NOT_READY routed in Generalist (domain
CLUSTER) — named in runbook, no specialist yet → empty plan. **VerificationResult is now the
projection of per-phase dossiers** via verifier public ready()/failed() factories — all 10 output
keys + handoffReady→nextStep (Stage B gate) + bootstrapStatus byte-identical; ClusterReadinessProjectionTest
pins it. ClusterReadinessResource = thin graph mirror (keeps dependsOn, no verify). runbook+generalist
threaded BootstrapPipeline→ResourcesStage→ResourceManager→ResourceCreationPipeline→stage. 30/30 green.

**Increment C delivered** (seed-master `controlplane.bdd`, DSL-first/offline): `ClusterReadinessPhase`
(kubeconfig/API/controllers) as BDD steps, each driven by an injectable `ClusterReadinessProbe`→
`Dossier`; the systemd-adapter dependency replayed via **`@NestedSteps`** (`WhenClusterReadiness`
injects systemd-adapter Given/When/Then with `@ScenarioStage`) = the cert-manager follow-the-chain
DAG edge, reusing the same Dossier/Symptom/Generalist machinery. `SimulatedClusterReadinessProbe.
failingAt(phase, symptom)` targets one phase. `NestedRunbookTest` proves nested render + targeted
fake incident + doctor diagnosis. **GOTCHA learned:** never read a JGiven Stage's captured-state via
a public getter — JGiven intercepts public stage methods as STEPS and corrupts the model flush; use
the probe-holder seam (as `SystemdAdapterStage` does). 41/41 seed-master green.

**Increment B delivered** (seed-master `controlplane.bdd`): the doctor. `Dossier` (typed successor
of the probe's Map envelope — status, `Optional<Symptom>`, summary, details + `toOutputMap()`);
`Generalist` (deterministic symptom→`SpecialistDomain` routing → `RemediationPlan`); `Specialist`
interface = the AI-ready seam (`Optional<Prescription> diagnose(Symptom, Dossier)`);
`DbusTcpSpecialist` (connection-refused → `RESTART_UNIT`); `Prescription` + `RemediationProgramRef`
(typed catalog, no magic strings) + `RemediationPlan`. **R3 Map→Dossier retype done atomically, no
shim** — probe/gate/When/Then/fakes/sim/stage-sink moved together; sink converts via `toOutputMap()`
so Pulumi outputs are byte-identical (retype confined to the BDD layer). Checkpoint consults the
Generalist on failure (`consultDoctor`, dossier stashed even when the Then throws), logs `⚕`/`℞`
inline. **DEFERRED to Increment C:** the plan flowing into the runbook *node's* Diagnosis/Mitigation
sections (needs the shared report-node model that arrives with #2's DAG — same place the A
edge-recording work was deferred). `DoctorTest` + retyped tests; 24/24 seed-master green.

**Increment A delivered** (seed-master `controlplane/bdd` + `pipeline` + `policy`):
`RunbookRenderer` (JGiven `ScenarioJsonWriter` → `AsciiDocReportGenerator`; emits a *directory*
`target/runbook/adoc/` with `index.asciidoc`, not one file), caller-owned shared `ReportModel`
(`BootstrapStage` creates it, threads via `PipelineState.recordingInto`, renders in a `finally` so a
CRITICAL throw still renders), typed `Symptom` carried in the probe envelope, and **preview-only**
fault simulation. Key design correction made during the work: `simulate` moved from
`policy.readiness.simulate` to **`policy.preview.simulate`** in a NEW `PreviewPolicy` (separate from
`ReadinessPolicy.override`) — preview-only *by construction* (the apply path never reads the
simulate map; engine `isDryRun()` is the sole gate). R6 test (`RunbookRenderingTest`) caught TWO
real empty-runbook bugs, both fixed: (1) `finished()` was skipped on failure → now in a `finally`;
(2) standalone-played model has null className → `RunbookRenderer.normalize()` names the feature.

**Note:** DAG-edges-from-`dependsOn` was DEFERRED to Increment C — only one checkpoint exists today,
so there are no edges to draw until checkpoint #2.

**DEFERRED (rule-of-three, decided 2026-06-06):** a *contributable fault-simulator seam* — a uniform
contract by which each BDD scenario owner declares how its scenario fails under simulation. Today
the stage hardcodes `SimulatedSystemdAdapterProbe::of` (one scenario). Considered building a
`ScenarioSimulation`-style seam now; decided NOT to — same rule-of-three non-goal as the harness
generalization (only one scenario; abstracting now guesses the wrong axes). Revisit at checkpoint #2.
Operator UX is already fine: `policy.preview.simulate.<scenario>: <kind>` in `Pulumi.dev.yaml`
(shipped commented as a template); preview-only by construction (apply never reads it).

**Read first:** `wip/spec.adoc` (the design) + `wip/README.adoc` (wip manifest). Lives in `wip/`
while in progress; durable substance migrates to `docs/architecture/` before merge (and `wip/` must
not reach main — [[wip-guard-hooks]] enforces this).

**What it is (two intertwined subsystems):**
- *Runbook* — the deliverable: a rendered `.adoc` DAG (git model) of the readiness scenarios played
  during a provisioning, with results and (on failure) diagnosis + remediation. Dynamic/on-demand;
  in preview the operator can order a *fake incident* (`policy.readiness.simulate.<scenario>`) to
  get a targeted runbook with no side effects.
- *Doctor* — the diagnosis engine consulted on scenario failure: Generalist → (deterministic
  routing) → Specialists → Prescription → RemediationPlan. Specialists read the captured snapshot
  first (cert-manager style), prescriptions are addressed to a remediation program via a typed
  catalog ref (not magic strings).

**Spec's three increments (its own A/B/C):**
- A — Runbook (shared ReportModel in PipelineState, DAG edges from existing dependsOn, AsciiDoc
  render, fake-incident ordering) — built on the EXISTING systemd-adapter checkpoint, no doctor yet.
- B — Doctor (Generalist/Specialist/Prescription/RemediationPlan; checkpoint consults on failure;
  DbusTcpSpecialist first).
- C — Checkpoint #2 (cluster-readiness) nested via @NestedSteps.

**CRITICAL cross-spec link — resolve before/while building the doctor core:** the config refactor
([[config-restructuring-state]]) declares **config missing-input remediation is the doctor's FIRST
use case** (its "Increment 2"), and the config entry gate already exists in `src/main`
(`controlplane/config/bdd/ConfigEntryGate`, asserts ready-vs-missing OUTCOME). So the doctor core
(Generalist, DomainSpecialist interface, Prescription, RemediationPlan) is shared between the two.
**OPEN QUESTION (must decide):** does the doctor core get built HERE (runbook-doctor) and config
Increment 2 consumes it, or is it a neutral shared module? The config spec leans "config is first
use case"; the runbook-doctor spec assumes the doctor is born in its Increment B. Reconcile the
numbering/ownership at the start of the work. Also: InfraDomain enum is designed to gain a
per-constant `specialist()` once the doctor types exist — that's the config↔doctor seam.

**Conventions in force:** [[sequential-no-compat-workflow]] (delete old paths same change, no
compat), BDD-in-main / TDD-in-test split ([[bdd-jgiven-test-strategy]]), JGiven stages must be
non-final (byte-buddy subclasses them), build via `flox activate -- ./mvnw -pl :seed-master -am
test -DskipTests=false` (reactor, tests skipped by default), Claude may run compile/test/preview.
