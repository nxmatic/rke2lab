# Handoff note — the host/OSGi frontier, re-thought as *library playability* + the in-world/out-world model

Status: HANDOFF (2026-06-21), authored from the `refactor/doctor-model-to-osgi` conversation while
executing the doctor-model extraction (plan: `main/.claude/plans/doctor-model-extraction-to-osgi-plan.md`).
This note is **read-only reference for the design workspace**. It records a frontier reframe that emerged
mid-refactor and the naming/structure decisions taken, so the *design* conversation can introduce the
in-world/out-world model in its proper place. Do not edit in-flight; reconcile at handoff time.

## Why this note exists

The extraction plan drew the host/OSGi frontier with a **describe-vs-actualize** lens ("the pure model
describes → osgi; observation/rendering actualises → host"). Executing it, the lens mislabeled real
files, and a sharper frontier surfaced. The mechanical move (48 pure model types → `doctor-core`) is
unaffected and proceeds; what changes is *how we decide what may follow*, and that decision is design's
to make.

## The reframe: the frontier is **library playability**, not folder, not "touches the world"

The true question for any type is: **can the third-party libraries it needs actually load and run
inside an OSGi bundle?**

- **NOT playable in OSGi** (hard frontier): `com.pulumi.*`, `StackHandle`/`StackSnapshot`/`StackHistory`
  (native deps, ServiceLoader, classloader assumptions); jgiven `com.tngtech.*` (scenario engine +
  `RunbookRenderer`).
- **Playable in OSGi** (often mistaken for host): plain `java.nio.file` **filesystem access is fully
  playable** — it's pure JDK. Touching the disk is *never* the disqualifier. Jackson is playable too
  (but in practice rides along with the pulumi adapter, so it lands out-world).
- **The detonator finding:** `BootstrapConfig` is a **pure record, zero external imports**. So every
  type filed as "host because it takes `BootstrapConfig`" is actually OSGi-playable. Under the
  playability lens these get *promoted*:
  - `ClusterReadinessProbe`, `SystemdAdapterProbe` → **ports** (pure functional interfaces)
  - `DbusTcpSpecialist`, `SimulatedClusterReadinessProbe`, `SimulatedSystemdAdapterProbe` → **core**
    (the Simulated* are pure preview/test fakes; only their *production* impls reach the live world)

The describe-vs-actualize lens flagged these as host because they *name* a host-shaped type. The
playability lens corrects it: the interface is a **port**, only the production impl that fetches the
live world is non-playable.

## The in-world / out-world model (the naming brainstorm)

Three module roles **per domain**, typed by what they may link against. The name encodes *direction of
intent* (where the operation goes), not *mechanism* (which library) — chosen deliberately:

```
<domain>-core      →  in-world operations    — the universe's own logic, pure, never leaves the bundle
<domain>-port      →  the membrane           — out-world ops DECLARED as contracts, seen from inside
<domain>-outworld  →  out-world operations    — those contracts MADE REAL, needs the non-playable libs
```

Key property that makes the name self-documenting: **the port is neither in nor out — it is the shape
of the out-world drawn from the in-world.** `core → port` (speaks the contract); `outworld → port`
(fulfils it); **`core` never sees `outworld`.** You cannot reach back into the world from outside it
without passing through the membrane — so an import from `outworld` into `core` is, by name alone,
obviously wrong.

Naming discipline to preserve: **`-outworld` means "crosses out to the runtime environment," NOT
"uses any non-JDK library."** jgiven + `RunbookRenderer` are non-playable too, but they *drive the BDD
harness* — they don't reach the world. Those are test/report scaffolding and stay in
`exec/seed-master/src/test`. If `-outworld` drifts into "anything non-pure" it loses its edge.

"Runtime environment" here = **the world we sit on** (the live cluster, the Pulumi state backend, the
dbus/systemd endpoint) — *not* process env vars.

## Decisions taken in this conversation (design may revisit, but these are the current intent)

1. **Adopt `-outworld`** as the name for the egress modules (the components that load & save the
   runtime environment). It reads honestly in a dependency list and forces the right question that
   `-adapter`/`-runtime`/`-host` let you dodge.
2. **Mutualize out-worlds by TARGET, not per domain** (user decision, 2026-06-21): one out-world module
   per external system the org talks to — **pulumi (state backend) / k8s cluster / systemd control
   nodes** — shared across domains, rather than a private adapter per domain. So the doctor's pulumi
   egress and any other domain's pulumi egress live in *one* pulumi out-world module. This supersedes
   the earlier "one `-outworld` per domain aggregator" sketch.
3. **Where the out-world modules physically live** (`osgi/<domain>/<domain>-outworld` as a plain jar vs
   `host/<target>-outworld` named by target) is **STILL OPEN** — left to the design workspace. Note the
   plain-jar precedent already exists under `osgi/` (`osgi/runtime`, `osgi/testkit` are
   `packaging=jar`), and the reactor order `osgi → host → exec` already permits either direction; an
   `osgi` module may depend up into `host/` (precedent: `osgi/testkit`).

## What the *code* increment is doing now (so design knows the seam state)

- Increment 3 = **only the 48 pure model types** moved to `doctor-core` (the clean, lens-agnostic move;
  valid under either frontier). The 3 readers became `public` (cross-package now).
- **Deferred to a later increment** (so this one stays a clean model move): split
  `RecordInterventionCommand` (pure `record(...)` core → core, host `main()` launcher → seed-master),
  and **promote the probe interfaces to ports + Simulated\* to core** under the playability lens.
- The snapshot seam (increment 4) still becomes a pure-record port (`SnapshotEntry`/`SnapshotView` /
  `SnapshotSource` + access/content exceptions) already defined in `doctor-port`; the pulumi-typed
  adapter is the canonical *first* out-world component.

## The single seam this informs

This is the prep that precedes introducing the **design runbook**. When design places the in-world/
out-world model, the doctor domain is the worked example: `doctor-core` (in-world model),
`doctor-port` (membrane), and a shared **pulumi out-world** holding `StackHandleSnapshotSource` +
`PulumiInterventionLedgerWriter` (the realized egress).

## Cross-refs

- Plan: `main/.claude/plans/doctor-model-extraction-to-osgi-plan.md` (the 5-increment extraction).
- Memory: rke2lab `designer-runbook-state`, `orchestration-purity-benefit`.
- Learned this session: the design→code seam is a **conversation boundary** (1 conversation = 1
  worktree = 1 workspace); this note is the artifact that carries the design decision across it.
