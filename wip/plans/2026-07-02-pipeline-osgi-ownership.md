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

- [x] **P1 — `Stage`→`Topic` scope DECIDED (2026-07-02, by the facts).** Criterion is NOT the module — it is *membership in OUR `during/then` grammar*: rename `*Stage`→`*Topic` for every unit of the fluent grammar; a `extends Stage<>` jgiven class KEEPS "Stage" (jgiven's word, reserved). Verified: seed-master's 8 = our grammar → `*Topic`; manifests-core's 4 (`Bootstrap/Network/Storage/Tools Stage`) also our grammar (`during(String topic, Function<XxxStage,XxxStage>)`, param already named `topic`, no jgiven import) → `*Topic`; `VaultStage` (probe-test) `extends Stage<VaultStage>` + imports jgiven → **stays `Stage`** (renaming it is the inverse error). S0 renames 12 (8+4), leaves `VaultStage`.
- [ ] **P2 — Settle the retrospective audit (atlas-first debt).** Is the multiplexor's world-boundary (`awaitService` + `Document`, `multiplexor-two-models-design`) the SAME seam as the pipeline's port-factory `forMode(intention)` + `RunMode→OSGi→runbook` joint, or distinct? Re-read `multiplexor-two-models-design` + remaining late-June memories. Livrable: verdict (same/distinct) graved; if same, reconcile the two designs. **Blocks S3.**

## Thread S — structure (strict dependency order)

- [x] **S0 — Lexical rename DONE 2026-07-02** (S0a `7b8811b0` seed-master, S0b `254b5795` manifests-core incl. the synthesizer). Built green with `-Dnullaway.skip` (the module's null-safety debt is a separate unfinished chantier — see below — orthogonal to a rename). NAMING TABLE (from the code, concrete — no "bootstrap" left bare):
  - *S0a — seed-master (do first, isolate risk):* `BootPipeline`→`FrameworkLaunchPipeline` (osgi/runtime/launcher); `BootstrapPipeline`→`ClusterSeedPipeline`; `BootstrapStage`→`ClusterSeedTopic`; the 8 other `*Stage`→`*Topic` (`Bbox`/`ClusterReadiness`/`Environment`/`Incus`/`Outputs`/`Preflight`/`Resources`/`SystemdAdapter`). Topic label `during("bootstrap")`→`"framework"` in the launch pipeline. Scope edits to seed-master + osgi/runtime/launcher + the 2 CLI callers of `BootPipeline` (word-boundary regex). → build green, commit.
  - *S0b — manifests-core (separate commit):* the 4 synthesis topics, renamed CONCRETELY (they synthesize node systemd infra, all-role server+agent, NOT "bootstrap"): `BootstrapStage`→**`Rke2InstallTopic`** (its core is `rke2lab-install` + env/config/link), `ToolsStage`→`ToolsTopic`, `NetworkStage`→`NetworkTopic`, `StorageStage`→`StorageTopic`. → build green, commit.
  - *Excluded:* `VaultStage` (`extends Stage<>` jgiven, probe-test fixture) keeps "Stage".
  - *Depends: P1.*
- [x] **S1 — Untangle the fold in `ClusterSeedTopic` DONE 2026-07-02** (`77fc9c81`). `runBootstrapPipeline()` split into two named gestures: `seedCluster()` (the framework-launch crossing) + `seedClusterWithinFramework()` (the reasoning body). Caller grammar aligned off the residual "bootstrap" homonym (`AwaitingBootstrap`/`BootstrapDone`→`AwaitingClusterSeed`/`ClusterSeedDone`, label `"bootstrap"`→`"cluster seed"`). Config-domain names (`BootstrapConfig`/`BootstrapOptions`) + the internal `"bootstrap resources"` topic left untouched. No behaviour change; built green (`-Dnullaway.skip`). *Depended: S0.*
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
