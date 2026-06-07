---
name: dsl-unification-topic
description: Parked future topic — unify the pipeline fluent DSL and JGiven BDD DSL into one language
metadata: 
  node_type: memory
  type: project
  originSessionId: be4e1ccc-a995-4154-b3d5-3e5ba252941b
---

PARKED future subject (not the active runbook-doctor branch). Capture lives at
`rke2lab/docs/architecture/patterns/dsl-unification-exploration.adoc` (REWRITTEN 2026-06-07), indexed in `docs/README.adoc`
with a NOTE marking it as a holding exception to the one-active-subject rule. Branch when started =
`refactor/jgiven-shared-engine`.

**Framing walked back twice during the 2026-06-07 brainstorm** (don't restart from the old "unify"
framing): (1) "unify is too strong" — pipeline is the *operational base*, BDD *rests on* it; (2)
"but don't re-code the BDD layer on our side" — JGiven already IS a narration+report engine. Landing
direction = **one engine (JGiven), two layers**: the fluent `during/then` grammar stays the
**authoring surface** (type-state, compile-time); JGiven is only the **runtime substrate**
(narration + shared `ReportModel`). Syntax vs engine — they never compete. This **supersedes** the
old Option-D / Shape-C ("pipeline becomes a scenario").

Chosen mapping = **Mapping B**: one run = one shared `ReportModel`, one JGiven scenario per topic
(`during(label)` → `startScenario(label)`). Action topics = `when`-only scenarios; verification
topics = full given/when/then + doctor. This is JGiven's natural granularity and **already** how the
2 checkpoints work (they `setModel(sharedReportModel)`) — generalise to all 8. The old big worry
(losing type-state ordering) **dissolves** under Mapping B.

**Two code corrections** the Explore pass found (old capture was wrong): scenarios do NOT extend
Pulumi ComponentResource — they're plain classes holding `Given/When/Then extends Stage<>` (the
ComponentResources are separate sinks; `bdd-diagnostic-pattern.adoc` is aspirational). And the
doctor is ALREADY implemented (`Generalist` + `DbusTcpSpecialist`, consult-on-failure seam live).

**New open decision** (replaces the type-state question) = the STATE model. JGiven threads state via
thread-locals (`@ProvidedScenarioState`) which conflicts with CLAUDE.md instance-passing discipline.
Role 1 (recommended): JGiven = narration+report only, inter-topic state stays `PipelineState`
instance, thread-local confined inside one verification scenario. Role 2: full JGiven state engine
(would need CLAUDE.md amendment). NOT decided — user wants a prototype to decide.

NEXT = prototype `PreflightScenario` (simplest action topic, `when`-only, narrating into the shared
`ReportModel`) before any spec. Lands AFTER [[runbook-doctor-state]] stabilizes — must preserve the
`consultDoctor` seam + shared `ConsultationLog`/`ReportModel` threading. Independent quick wins:
BootstrapStage is a meta-stage (wraps BootstrapPipeline); logging scattered (TopicRunner→SeedLog,
checkpoints→readinessLogger, action topics→nothing) — unify once all topics narrate one ReportModel.
