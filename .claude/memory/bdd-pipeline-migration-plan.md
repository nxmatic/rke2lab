---
name: bdd-pipeline-migration-plan
description: "★ GO reached 2026-07-04: the seeding pipeline migrates from the fluent-grammar/Topic-State model to jGiven scenarios orchestrated by the JUnit Platform launcher. The 7 go/no-go concerns are PROVEN (reference impl at tag spike/bdd-pipeline-poc). The FORWARD SEQUENCE: (1) spec-network legibility cleanup FIRST, then (2) ClusterSeed-vertical migration, one pipeline at a time, the other 6 coexisting on Topic/State. Authored in design/pre-integration on purpose — the plan must survive the deletion of feature/bdd-pipeline-poc."
metadata:
  node_type: memory
  type: project
---

## Where we are (2026-07-04)

GO reached. The BDD-as-engine turn is proven viable AND proven to hold on the atlas (four invariants:
two-spaces, R4 boot seam, `Document` seam, `DUPLICATE_REALM_CLASS`). The 7 go/no-go concerns are all
PROVEN on a disposable bench — reference impl pinned at [[spike-bdd-pipeline-reference-tag]].

**Honesty caveats to carry forward (not walls, but do not overstate):**
- Concern E (export/thread) and the cross-world graft are proven *in isolation* — `URLClassLoader`
  stand-in for the OSGi realm, in-JVM JSON round-trip. The MECHANISM holds; wiring it to the real
  embedded Felix (`ClusterSeedTopic.seedCluster()`) is part of the migration, not re-proof.
- `FailFastPreviewExecutor` is loquacity polish (render the would-run downstream as PENDING), not on
  the critical path.
- The DAG gate's topological check works on test-classpath bytecode; confirm on real staged bytecode.

## The forward sequence (graved, do NOT reorder)

1. **Legibility cleanup of the spec network — FIRST, before any migration code.** The spike-window
   framing (the "superseding turn" banner bolted atop the 878-line `pipeline-spec.adoc`, the Diagram Q
   + go/no-go table in `atlas/host-pipeline.adoc`, the `bdd-pipeline-poc-design.adoc` record) made the
   docs HARDER to read — you can't tell the current model from what replaces it. Cleanup = make the
   new BDD-as-engine model the PRIMARY readable content; keep the old Topic/State model ONLY as the
   "avant" reference in the avant/après. Whole network: `pipeline-spec.adoc`,
   `patterns/fluent-pipeline-grammar.adoc`, atlas `host-pipeline`/`runtime`/`doctor`/`integration-atlas`.
   Rationale: don't build the migration on docs nobody can read.
2. **ClusterSeed-vertical migration — one pipeline at a time.** Migrate ONLY `ClusterSeedPipeline`
   first (the POC transposed it), end-to-end into `seed-master` (real Felix, not the stand-in): the 7
   mechanisms wired live. The other 6 pipelines STAY on Topic/State meanwhile — assumed coexistence,
   NOT a legacy variant to hide. Each subsequent pipeline = its own increment. Consult
   [[spike-bdd-pipeline-reference-tag]] for the proven mechanism implementations.

## Settled decisions (do NOT re-litigate)

- Scenarios ARE the execution engine; engine = jGiven; JUnit Platform launcher = the orchestration
  substrate (already embedded via `InContainerJUnitRunner`); the fluent `during`/`then` grammar
  dissolves; a phase is a nested STAGE in ONE composing scenario (state scoped by the stage/step
  hierarchy — the fan-in works, the D blocker dissolved); cross-world edges cross as a serialized
  `ScenarioModel` grafted via `addNestedStep` (the F blocker dissolved — `executionStatus` carries the
  verdict). RunMode is re-posed as an `ExecutionCondition` + the `PreviewExecutor`; `LiveGate` is erased.
- The compile-time DAG safety is KEPT via a build-time ASM gate on `@Provided/ExpectedScenarioState`
  order (not runtime-only) — proven by `DagGateSpikeTest`.
- Spock stays OUT (no data-table need identified; Groovy realm cost); no home-grown BDD engine.

## What NOT to keep

`feature/bdd-pipeline-poc` (the bench branch) is deleted after the durable design record is lifted
into `docs/` on `design/pre-integration`. Its code survives only at tag `spike/bdd-pipeline-poc`. The
bench module itself is NOT integrated (disposable, spike-tagged, out of the default build).

See [[spike-bdd-pipeline-reference-tag]] [[specs-current-at-brainstorm-end]] [[verify-state-before-labeling]].
