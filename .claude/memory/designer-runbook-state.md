---
name: designer-runbook-state
description: "The designer's runbook chantier (2026-06-21): a LIVE render of the design state, the first materialization of the re-entrance north-star. SPEC graved in docs/architecture/designer-runbook.adoc (+ v0 how). Inventory done. Coverage computed: 0/38 implemented as scenarios yet, ~24/38 would pass LIVE today. Worktree feature/designer-runbook-v0 set up; we are at SPEC level still (inventory + coverage = design; probing the reactor = the code half, just starting). DRY-RUN = all scenarios we WILL have; LIVE = those green today."
metadata:
  node_type: memory
  type: project
---

## What it is

The designer's runbook = a live render of the DESIGN state, the way the operator runbook is a live
render of the runtime state. It pays the re-entrance debt ([[hub:reentrance-northstar]]): the atlas is
hand-written and can drift; the runbook re-derives its playable half from the code so it cannot lie.
SPEC = `docs/architecture/designer-runbook.adoc` (committed on main, `4d894a69` + back-link in
`integration-atlas.adoc`). Engine = JGiven, ALREADY WIRED (jgiven-core + jgiven-junit5 in BOM +
exec/seed-master, `jgiven.report.dir` configured, ~18 scenario classes under `controlplane/bdd/`).

## The two run modes (user's framing, load-bearing)

* *DRY-RUN* — every scenario we have EXPRESSED (the full list), shows what the runbook will look like
  when everything is in place.
* *LIVE* — the scenarios that PASS against the real reactor today = the true state of advancement. A
  scenario probes the code; green = the fact holds, red = drift. Starting with few/no greens is NORMAL
  and correct — it is the honest baseline.

## A capability sentence IS a scenario

GIVEN the constellation before / WHEN a term or model lands / THEN the capability, AND additivity. The
capability map + additivity proof are the THEN and AND of one design scenario. We already play these in
prose; the runbook renders them as JGiven scenarios whose steps probe the reactor.

## Inventory + coverage (computed 2026-06-21)

Scenarios already EXPRESSED across the 4 atlas per-subsystem views (not yet implemented as JGiven):
- doctor ~15 (≈11 shipped) · runtime-OSGi ~11 (≈9 shipped) · config ~5 (≈3 shipped) · unitrepo 7 (1 + 1 partial).
- **Total ≈ 38 expressed; ≈ 24 would pass LIVE today (~63%); 0 implemented as runbook scenarios yet.**

The unitrepo 7 (from the capability tables in model-overview + atlas), with playability:
1. any unit set resolves in one Felix resolve — now/blue — PLAYABLE (UnitResolver exists; 46 units on bioskop)
2. a checkpoint is a foldable Visit-unit DAG — next/green — PARTIAL (MedicalRecord fold shipped; ingest not)
3. a handler loads from the store at runtime — next/green — narrated (load-from-store unbuilt)
4. the model decides what, the host actualises how — next/green — narrated (ResourceDescription/ACL unbuilt)
5. recruit a specialist by pulling its closure — horizon/amber — narrated
6. share a severed, gated lens of a DAG — horizon/amber — narrated
7. reproduce a deploy byte-exact — horizon/amber — narrated

Extra PLAYABLE facts already graved elsewhere (fully probe-able now): walker retired
(`ManifestsUnitDependencyApplier` absent); `unitrepo-core`/`unitrepo-handler-spi` exist as bundles;
`osgi.extender=unitrepo.handler` exported; `manifests-port`/`netplan-port` exist (DIP).

## Where we are (honest)

STILL at SPEC level. Inventory + coverage = design. The code half (a `DesignProbe` JGiven stage whose
steps grep/count the reactor + a `DesignStateScenarioTest`) is JUST STARTING in worktree
`feature/designer-runbook-v0` (off origin/main, sops re-smudged). The runbook must eventually cover ALL
4 views, not just unitrepo — that is the real coverage gap (the ~31 doctor/config/runtime sentences are
expressed in the atlas but outside the runbook).

## NEXT

Implement the PLAYABLE unitrepo scenarios first (the ~5 that probe the code), render the JGiven report
= the runbook v0; then attach the narrated ones marked by horizon; then widen to the other 3 views. See
[[hub:reentrance-northstar]] [[unitrepo-design-unification-state]] [[bdd-jgiven-test-strategy]]
[[dsl-unification-topic]].
