---
name: runmode-livegate-pulumi-abstraction
description: "ClusterSeed design (2026-07-06): RunMode is a tri-state FACT (STANDALONE|PULUMI_PREVIEW|PULUMI_RUN) that lives at the Pulumi EDGE — Pulumi is implementation, the domain must NOT see it. Only PULUMI_RUN mutates master; standalone+preview = inert probes + pending-marking, complete runbook, zero mutation. The domain consumes ONE abstract face — LiveGate (isOpen/through) — never RunMode. LiveGate is a projection of RunMode. Supersedes the committed 2-boolean RunMode (4b40a9d) which must be reworked."
metadata:
  type: project
---

**The fact (tri-state, kills the impossible 4th combo).** Task 2 committed `RunMode(boolean pulumiMode,
boolean previewMode)` — two free booleans, one impossible combo (standalone+preview). Real domain is
TRI-STATE: `STANDALONE | PULUMI_PREVIEW | PULUMI_RUN`. Only `PULUMI_RUN` (= `pulumi up`) provokes real
provisioning. Standalone + preview = inert probes + pending-marking executor → COMPLETE runbook, ZERO
mutation. (Why standalone ≠ live, user's collision scenario: standalone creating incus `master` for real
→ not in any Pulumi state → later `pulumi up` hits "already exists"; `refresh` won't import it. So
standalone MUST be inert.)

**Truth table (the only thing to memorize):**
- STANDALONE     → probes inert, pending, no mutation, output=print
- PULUMI_PREVIEW → probes inert, pending, no mutation, output=export (dry-run)
- PULUMI_RUN     → probes live, normal, mutates master, output=export

**Pulumi is IMPLEMENTATION — the domain must not expose it (user's decisive point).** A phase must NOT
read `PULUMI_PREVIEW`/`playsLive()` — that leaks Pulumi vocabulary into domain logic. Inversion of
dependency: the domain depends on an ABSTRACT face, the Pulumi edge produces it.
- `RunMode` (Pulumi vocabulary) = a DETAIL, lives at the edge (`pulumi-edge`, beside `LiveGate`); the
  DETECTION (`Deployment.getInstance().isDryRun()`) is Pulumi-pure and lives there too — the ONE place.
- The domain consumes `LiveGate` (`isOpen()` / `through(live, deferred)`) — its type surface names NO
  `com.pulumi`. `LiveGate` already exists and is already abstract → it's the face to consume.
- `LiveGate` becomes a PROJECTION of `RunMode` (`LiveGate.forRun(RunMode)` replacing `forRun(boolean)`),
  which finishes the factorization LiveGate's javadoc promised (solves the 3 inline
  `Deployment.isDryRun()` reads in `IncusResourceBootstrap`).

**ONE abstract face only (user, settled).** `LiveGate` suffices — "live vs deferred" is the only axis the
domain needs, and it produces the expected runbook (deferred = bodies don't touch reality but the
scenario still plays → tree builds → complete runbook; that IS `through(live,deferred)`).
`PendingMarkingScenarioExecutor` stays an implementation detail BEHIND the gate (deferred → the edge/seeder
installs it; the domain sees only "deferred"). NO separate render-mode exposed now (YAGNI). If ever
needed: a phase asks `adapt(RenderMode.class)` — the [[classrealm-adaptable-pattern]] IS the extension
tool. Door left open AND tooled.

**Source-vs-projection (the pattern, twice).** RunMode is the SOURCE (fact); LiveGate/probes/executor/
export are PROJECTIONS. Reading RunMode tells you everything; nothing re-decides in its corner. Same shape
as [[classrealm-adaptable-pattern]] (a world → its faces) — the recurrence is why it feels right.

**TODO for the rework:** move/rewrite `RunMode` → tri-state enum at the edge; `LiveGate.forRun(RunMode)`;
phases consume `LiveGate`, never `RunMode`. See [[cluster-seed-transport-consensus]]
[[cluster-seed-execution-state]].
