---
name: pipeline-state-per-topic-io-refactor-backlog
description: "BACKLOG (design, raised 2026-07-02 by the user during the null-cleanup): the pipeline state should be GENERIC per topic — State<I,O> where each topic consumes its own input record I and produces its own output record O, the builder folding O_n into I_{n+1}. The null-cleanup extracted a FLAT PipelineInputs + StateBuilder instead (all topics' inputs/outputs merged into one sack) — a solid null-safe base, but it means the current state is 'the merge of all pipelines': PreflightTopic sees doctor/readinessAuthority/bootstrap it has no business with. Dissociating the states is the next chantier, its own design, NOT a null-cleanup side effect."
metadata:
  node_type: memory
  type: project
---

## The user's framing (2026-07-02)

> "le state est parametre par deux records (input, output) et un builder — `State<I,O,Builder<I,O>>`.
> la tu as merge tous les inputs et outputs, du coup le pipeline ici c'est le merge de tous les
> pipelines." … "termine [le null-cleanup] si tu y arrives, ca donne deja une base solide pour les
> nullables. et apres on reprend le chantier pour dissocier les states."

## What the null-cleanup did (the flat base)

To kill the `PipelineState` "field not initialized" NullAway warnings, `PipelineState` was split into:

- `PipelineInputs` — an **immutable record** with EVERY topic's inputs flattened together
  (config, policy, options, doctor, systemdRuntimeStatus, readinessAuthority, …), assembled via a
  `Builder` (`@MonotonicNonNull` fields) frozen at the `running…()` transition.
- `StateBuilder` — a **mutable** builder holding EVERY topic's output (bbox, bootstrap,
  systemdAdapterLaunch, resources), `@MonotonicNonNull` + guarded accessors.
- `PipelineState = inputs + builder + runner`.

This is null-safe and matches the grammar doc's "State shape" section as written — but the section
itself describes the FLAT model. It is a solid base, not the target.

## The defect it masks (why the next chantier)

The state is **flat**, so it is the merge of all topics' I/O. `PreflightTopic` reads
`state.inputs.config()` fine, but the same `inputs` also carries `doctor`, `readinessAuthority`,
`bootstrap` output, etc. — things Preflight has no business seeing. There is no per-topic narrowing:
every topic sees the whole sack. The type-state classes (`AwaitingBbox`, `BboxDone`, …) sequence the
topics but do NOT narrow what each can read.

## The target — `State<I, O>` generic per topic

Each topic declares its own input record `I` (only what it consumes) and output record `O` (only what
it produces). The transition folds `O_n` into `I_{n+1}` — "the output of the current state is the
builder of the next" ([[null-safety-set-once-fields-monotonic]] and the grammar doc's State-shape
section state this at the value level; this chantier lifts it to the TYPE level). Then a topic can only
touch its own inputs; the compiler forbids reaching for another topic's data. This is a refactor of the
**fluent-pipeline pattern itself** — it touches `docs/architecture/patterns/fluent-pipeline-grammar.adoc`
(the grammar) and every pipeline that uses it, not just `ClusterSeedPipeline`. Sequenced AFTER the
null-cleanup; needs its own design pass (start from the grammar doc, per the atlas-first reflex).

See [[null-safety-set-once-fields-monotonic]] [[null-safety-optional-from-source-to-resolver]].
