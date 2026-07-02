# Pipeline — OSGi ownership & the three levels — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the pipeline toward its designed target — OSGi owns the grammar + engine + reasoning; the host is a consumer (boots the framework, injects `RunMode` + Pulumi context, renders/writes). Untangle the `Boot`/`Bootstrap` homonym into three named levels and realise them in dependency order, without ever letting the mechanical rename clobber unrelated code.

**Design of record:** `docs/architecture/osgi/pipeline-spec.adoc` (read the TL;DR + Figures 2b/2c first). Memory: `pipeline-jgiven-separation-design`. This plan is the task decomposition of that spec's "Work plan" section.

**Two orthogonal threads — do NOT braid.** Thread S (structure) and Thread R (reliability) are independent; R touches topic bodies, S touches structure. Each task = one green commit (build + `pulumi preview` where relevant) before the next.

## Global Constraints

- **Build through flox always:** `flox activate -- ./mvnw …`. Never `mvn install` to `~/.m2`; inter-module deps resolve through the reactor — every module build uses `-am`. Build `-Pall-worlds` (NEVER `,nxmatic`). Measure NullAway with `clean package -DskipTests=true`.
- **seed-master uses `package`, not `compile`** (the `stage-embedded-bundles` goal needs it).
- **Own external worktree per this chantier** (external model, sibling of `main`, not `.claude/worktrees/`). Re-smudge sops on creation.
- **No blind sweeps.** The `Stage`→`Topic` rename is transverse (14 `*Stage` over 4 modules; `BootstrapStage` exists TWICE — seed-master AND manifests-core). A blind `perl -i` clobbered manifests-core once this session — REVERTED. Scope every rename explicitly (P1).
- **Stay design-first between S-tasks:** each S# that rewrites structure (S2, S3) reopens the atlas before coding.

## Preludes — decide BEFORE any `git mv`

- [ ] **P1 — Decide the `Stage`→`Topic` scope.** All `*Stage` across the 4 modules (seed-master 8, manifests-core 4, probe-test 1), or seed-master only? `manifests-core`'s `BootstrapStage`/`NetworkStage`/`StorageStage`/`ToolsStage` are synthesis steps, not jgiven-narrating topics — argue whether "Stage is a jgiven word" applies to them. Livrable: one decision line in the spec. **Blocks S0.**
- [ ] **P2 — Settle the retrospective audit (atlas-first debt).** Is the multiplexor's world-boundary (`awaitService` + `Document`, `multiplexor-two-models-design`) the SAME seam as the pipeline's port-factory `forMode(intention)` + `RunMode→OSGi→runbook` joint, or distinct? Re-read `multiplexor-two-models-design` + remaining late-June memories. Livrable: verdict (same/distinct) graved; if same, reconcile the two designs. **Blocks S3.**

## Thread S — structure (strict dependency order)

- [ ] **S0 — Lexical rename (scoped by P1).** `git mv` the 3 classes: `BootPipeline`→`FrameworkLaunchPipeline` (osgi/runtime/launcher), `BootstrapPipeline`→`ClusterSeedPipeline`, `BootstrapStage`→`ClusterSeedTopic` (seed-master). Rewrite identifiers in the ~18 referencing files **bounded to the P1 scope** (word-boundary regex, verify no manifests-core collateral). Topic label `during("bootstrap")`→`"framework"` in the launch pipeline. Then `*Stage`→`*Topic` per P1 scope. → build green, commit. *Depends: P1.*
- [ ] **S1 — Untangle the fold in `ClusterSeedTopic`.** Today one method nests framework-launch (`FrameworkLaunchPipeline.embedded().during(...)`) around the reasoning body via a lambda. Name the two altitudes apart: the framework-launch crossing vs the cluster-seed reasoning body — two explicit, named gestures. No behaviour change. → commit. *Depends: S0.*
- [ ] **S2 — Contributable seed.** `ClusterSeedPipeline` becomes a shared boot PREFIX + a per-seed contributed TAIL (seed-master / manifests-cli / netplan-cli each contribute their topics). The 3 seeds already share `FrameworkLaunchPipeline.embedded().during(...)` — only the tail differs (proven in Figure 2c). Trades type-state rigidity for an ordered list of contributed topics — a real rewrite. *Sub-tasks to detail when reached (reopen atlas first).* *Depends: S1.*
- [ ] **S3 — Decision into OSGi.** Re-seam the 4 topics that import Pulumi/gRPC (`ClusterReadinessStage`, `EnvironmentStage`, `OutputsStage`, `SystemdAdapterStage` — pre-rename names) behind ports; the orchestrator becomes an OSGi capability, actualisation bodies stay host. Realise the port-factory `PipelineProvider.forMode(intention)` (host asks intention, OSGi resolves via LDAP `(probe=…)`); `TopicContext` injected for run data. Moves initiation rows host→OSGi. *Depends: S2 + P2.*
- [ ] **S4 — Boot-executor unification.** One `boot-pipeline` for prod (`OsgiRuntime`) + test (`FelixFrameworkExtension`), beside `boot-discovery`. Parallelisable (own concern). *Depends: S0.*
- [ ] **S5 — Transverse.** Separate the pure jgiven scenario MODEL from the host rendering ENGINE (runbook → pure bundle, scenario → resolvable `UnitResource`); dissolve `osgi/jgiven/` INTO `pipeline`. *Depends: S3.*

## Thread R — reliability (orthogonal, startable any time)

The arc that started it all, fixed in reverse (`non-null → pulumi-outputs → jgiven → pipeline`). Uncommitted WIP already on disk: the `pulumi-edge` trio (`LiveGate`, `PulumiOutputContributor`, `PulumiOutputRegistry` — unwired) + the readiness edits.

- [ ] **R1 — Undo the fake-green + delete dead dry-run.** Unwind `SimulatedClusterReadinessProbe.deferred()` + the `checking()` symptom tweak (they render PASSED/fake-green — the dishonesty to avoid). Delete the dead `System.setProperty(JGIVEN_DRY_RUN)` in both stages (verified dead on our `startScenario(String)` path). Preview renders honestly (PENDING) via the living gate deferring the probe. → preview green, commit.
- [ ] **R2 — `SystemdRuntimeStatusReport`.** Producer returns the typed record (no map); `SeedNodeBootstrapWatcher` reads it typed — the 3 converter helpers + `@Nullable Object` params deleted at the source. → green, commit.
- [ ] **R3 — Output registry.** Wire `PulumiOutputContributor`/`PulumiOutputRegistry`; migrate the 4 summaries to contributors; `ResourceCreationResult` drops raw maps; `OutputBuilder` melts into `registry.add(...).assemble()`. → green, commit.
- [ ] **R4 — Sweep the remaining `isDryRun()` sites** onto the mode axis (Incus/image/bbox). → green, commit.

## Critical path & sequencing

```
P1 → S0 → S1 → S2 → S3 → S5
                    ↑
              P2 ───┘         S4 ∥ (parallel, after S0)

Thread R (R1→R2→R3→R4) is independent — run any time.
```

- P1 gates everything (the vocabulary). P2 gates S3 (the seam reconciliation).
- S2 and S3 both rewrite `ClusterSeedPipeline` — settle order when S2 starts (contributable before or with the OSGi move).
- The open `RunMode → OSGi → runbook` trigger (detached mode) is decided inside S3.

## Verification gate per task

- Compiles: `flox activate -- ./mvnw -pl :seed-master -am package -DskipTests -Pall-worlds` (green, no `Tests run` needed for compile-only).
- Behaviour (R-thread + S3): `pulumi preview` — the kubeconfig hang is gone, the runbook shell renders honestly.
- No collateral: after any rename, `git grep` the old identifiers == 0 AND `git diff --stat` touches only the intended module.
