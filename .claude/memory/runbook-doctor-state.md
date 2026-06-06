---
name: runbook-doctor-state
description: feature/runbook-doctor — Increment A DONE & committed; Increment B (the doctor) is next
metadata: 
  node_type: memory
  type: project
  originSessionId: fecaba54-8122-4dc1-8916-743ef5d2dec0
---

Active chantier on branch **`feature/runbook-doctor`**: build the runbook + doctor subsystem.
Design DONE; **Increment A DONE, verified & committed** (commit 3b46b249, 2026-06-06); **Increment
B (the doctor) is next**.

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
