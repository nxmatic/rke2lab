---
name: pipeline-orchestration-osgi-vision
description: "VISION/CARTO (user, 2026-06-20 — his real model of the system/universe, surfaced because R4 SHOCKED him) for moving the orchestration LOGIC into the OSGi world. The malaise R4 exposed: the OSGi world ended up holding mainly a DATA MODEL that describes (manifests/blueprints) in service of Pulumi, while the LOGIC stays host-side — backwards, since OSGi is the reactive, service-shaped world (SCR/@Reference/registry/lifecycle) and SHOULD carry the decision logic. The resolution (his framing): the pipeline DSL (the fluent type-state grammar, already documented) becomes an OSGi capability — the ORCHESTRATOR runs as an OSGi service, the pipeline still 'runs' but calls OSGi service-capabilities; the actualisation steps (Incus/Pulumi/gRPC) stay HOST but are exposed as PORTS the OSGi orchestrator invokes. NOT 'all logic → OSGi' (gRPC actualisation stays host by #1565); it is the DECISION logic that is mis-placed. Factual audit done: stages already mostly delegate (IncusStage = pure coordinator, new IncusResourceBootstrap is THE inversion point), but 4/~10 stages still import Pulumi/gRPC (e.g. EnvironmentStage takes a Context just to log) — so it needs a real inversion pass, not a move. SEQUENCE: right AFTER the CLI migration (which proves the multi-entrypoint boot this rests on). NOT coded."
metadata:
  node_type: memory
  type: project
---

## The malaise (user, 2026-06-20) — his real vision of the system/universe

R4 SHOCKED him on exactly this: as shipped, the **OSGi world holds mainly a DATA MODEL that describes**
(manifests, blueprints) **in service of Pulumi**, while the **main logic stays in the HOST world**. That
is backwards relative to the nature of the two worlds — OSGi is the more reactive world and covers the
SERVICE model very well (SCR, `@Reference`, the registry, lifecycle). The reactive, service-shaped world
should carry the decision logic; it should not be a data provider to a host that decides everything.

His resolution, stated as a question that dissolves the hexagonal paradox:
> "le DSL que nous avons identifié dans les pipelines ne devrait-il pas être implémenté dans le monde
> OSGi ? le pipeline tourne dans le monde Host, mais fait appel à des services/capacités OSGi ?"

This is the **application-service** pattern: the pipeline sits ON the frontier (it DECIDES = domain
nature, and it DRIVES actualisation = host nature), which is why it feels mis-placed wherever it sits.
Split it by nature:

- the **orchestration DSL** (the fluent type-state grammar `.during().then()`, topics, gates,
  sequencing) = pure decision → an OSGi **capability/service** (reactive, pluggable);
- the **actualisation step bodies** (talk to Incus/Pulumi/gRPC) = adapters → stay HOST, exposed as
  **ports** the OSGi orchestrator calls via the registry (DIP).

Net: **OSGi orchestrates** (decides order, gates, the what), **host actualises** (executes the gRPC
effects) by implementing injected ports. This finally uses OSGi for what it is best at and is the same
direction the R4 "3 wrong-direction inversions" already pushed — at larger scale.

## Distinguish (do NOT over-reach): two natures of "logic"

- **Actualisation logic** (imports `com.pulumi`/`io.grpc`, mutates resources) = LEGITIMATELY host —
  gRPC cannot enter a bundle (#1565 / TCCL). NOT mis-placed; it is the south adapter by necessity.
- **Decision logic** (policies, gates, sequencing, "what to synthesize, in what order, given what
  state") = NO gRPC dependency → THIS is what is mis-placed host-side and what the vision moves into
  OSGi. (grep 2026-06-20: 26 host files import pulumi/grpc; a long list of stages/policies/gates import
  NEITHER — PreflightStage, IncusStage, BootstrapStage, ControlplanePolicy, TargetReloadPolicy, the
  gates… — the candidate decision surface.)

## Factual audit (integration @717943b5, 2026-06-20) — realistic, with caveats

GOOD (makes the vision feasible, not utopian):
- The DSL is ALREADY a named, documented object: `docs/architecture/patterns/fluent-pipeline-grammar.adoc`.
- The exemplar `IncusStage` is a PURE coordinator: imports zero Pulumi; its whole body is
  `sink.accept(new IncusResourceBootstrap(config, osgiRuntime).apply(policy))`. The actualisation is
  already encapsulated in the `IncusResourceBootstrap` adapter. The only host coupling is the `new
  IncusResourceBootstrap(...)` — THE inversion point (same class of defect as R4's 3 inversions: it
  would become a `@Reference`/injected port).

CAVEATS (why it is a real inversion pass, not a move):
- NOT all stages are pure coordinators: **4 of ~10 import Pulumi/gRPC directly** — `ClusterReadinessStage`,
  `EnvironmentStage`, `OutputsStage`, `SystemdAdapterStage`. E.g. `EnvironmentStage` takes a
  `com.pulumi.Context` just to log — actualisation leaking INTO a stage. These need real re-seaming
  before the orchestrator can move.
- Most `new X()` in stages are DATA models (`ReportModel`, `ConsultationReport`, `CommandResult`) — NOT
  adapters, NOT inversion points. The genuine adapter-construction to invert is the Incus one (and
  whatever the 4 mixed stages do with their Context).
- Open question: is bootstrap SEQUENCING itself domain logic, or is it actualisation (a sequential
  effect process)? The type-state grammar argues it is expressible as pure decision, but this must be
  settled per stage, not assumed.

## Sequencing — the user's call (2026-06-20)

Carto NOW (this note); **act right after** — it is his real target. The CLI migration
([[cli-osgi-migration-carto]]) is the natural PREREQUISITE: it proves the multi-entrypoint boot
(OsgiRuntime parameterised per entrypoint) that an OSGi-resident orchestrator rests on. So: CLI
migration → then this. Do NOT open this front before R4's tail (CLI) is closed — avoid stacking two big
fronts ([[migration-branch-no-fallback]] spirit). When it starts, it gets its own design slice + worktree
(read-only carto deepening first: audit ALL stages for the decision/actualisation seam, list every
adapter-construction to invert, pick where the OSGi orchestrator lives — likely a new osgi/ orchestration
bundle exposing the grammar as a capability, with host adapters as ports).

See [[cli-osgi-migration-carto]] (the prerequisite), [[osgi-runtime-r4-boot-seam-state]] (the boot it
rests on + the 3 inversions exemplar), [[api-extraction-tri-carto-state]] (the port-vs-impl sort),
[[system-space-world-universe-glossary]] (osgi describes / host actualises — this extends the verb set:
osgi could also ORCHESTRATE), the fluent-pipeline-grammar doc (the DSL being relocated).
