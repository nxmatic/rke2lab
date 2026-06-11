---
name: branch-namespaces
description: "rke2lab branch-naming convention: kind-prefixed branches (feature/, refactor/, deprecated/). NEW spike/ namespace for throwaway-or-promote proofs. The prefix names the NATURE of the work, not just the topic."
metadata: 
  node_type: memory
  type: project
  originSessionId: c3cdc9ef-2759-4a4c-91b6-06d10b0c9df6
---

rke2lab names branches by the **nature of the work**, kind-prefixed (verified in `git branch -a`):
`feature/` (shippable work), `refactor/` (refactors, e.g. `refactor/config`,
`refactor/jgiven-shared-engine` from [[dsl-unification-topic]]), `deprecated/` (archived lineage),
plus subtree/submodule remotes (`fleet/`, `nix-darwin-home/`) and `dependabot/`.

**NEW (2026-06-10): `spike/` namespace** for a throwaway-or-promote architectural proof — a branch
whose job is to ANSWER A QUESTION ("can the architecture do X?"), not to merge as-is. First one:
`spike/doctor-cohort-correlation` (proving cross-patient medical-record correlation before the
HealthSystem layer commits to it — [[healthsystem-access-control-model]]).

**Spike vs PoC (investigated; spike chosen on the merits):** a *spike* (XP term) = a time-boxed,
narrow, internal, **throwaway-by-default** probe that reduces uncertainty, learning captured then
code discarded. A *PoC* = broader, often stakeholder-facing, demonstrates feasibility and is often
kept. This repo's own precedent is the spike pattern: `wip/sandbox` (lock-free self-read) and the
`sref-producer`/`sref-consumer`/`sandbox-selfread` probe stacks were throwaway-and-deleted (commit
`f1f4dc88` removed the sandbox after the learning landed in docs/memory). So a question-answering
probe here = `spike/`; if it earns promotion, cut a `feature/` branch from the learning.

**Mechanics that compose with this:** the wip-guard hooks key off the `wip/` DIRECTORY, not the
branch name ([[wip-guard-hooks]]) — so brainstorm→design→plan artifacts live under `wip/` on ANY
branch (spike/ included) and are blocked only from reaching main. Durable findings migrate to
`docs/` before any promotion; the spike's code itself may be deleted.

**`@Spike("spike/…")` annotation (durable repo convention, added 2026-06-10):** the source-level
marker for spike CODE, at `io.nxmatic.rke2lab.controlplane.meta.Spike` (seed-master) — the repo's
FIRST custom annotation. `@Retention(SOURCE)`, targets type/method/ctor/field/param, `value()` =
the spike branch. Replaces ad-hoc prose `SPIKE (...)` tags: the footprint is greppable
(`grep -rn '@Spike('`) AND machine-checkable, so a future guard can refuse `@Spike` reaching main —
the source analogue of the wip/ dir guard (not yet built; the hook would mirror
`.githooks/lib-wip-guard.sh`). At promote-time, strip the annotation; at discard-time, grep finds
every spot. First use: the 3 cohort-correlation spots (`Generalist.cohortFinding`,
`LiveMedicalRecordRegistry.cohortFor`, the `MedicalRecordRegistry.cohortFor` default).
