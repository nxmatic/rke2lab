---
name: runbook-doctor-state
description: feature/runbook-doctor — Increments A,B,C,D all DONE & committed; NEXT = shared report-node model (node-level Diagnosis/Mitigation + explicit dependsOn edges)
metadata: 
  node_type: memory
  type: project
  originSessionId: fecaba54-8122-4dc1-8916-743ef5d2dec0
---

Active chantier on branch **`feature/runbook-doctor`**: build the runbook + doctor subsystem.
Design DONE; **A DONE** (3b46b249), **B/doctor DONE** (aa55ced4), **C/checkpoint#2-nested DONE
DSL-first** (9685793c), **D/live-cluster-wiring DONE** (fc91f127, 2026-06-06). **NEXT = the last
deferral**: the shared report-node model so the plan renders into each runbook node's
Diagnosis/Mitigation sections (vs. today's inline log) and records explicit `dependsOn` DAG edges
(today the edge is shown via @NestedSteps nesting). Now unblocked: both checkpoints are live BDD
scenarios.

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
