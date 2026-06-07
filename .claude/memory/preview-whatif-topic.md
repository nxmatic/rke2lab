---
name: preview-whatif-topic
description: PARKED exploration (2026-06-07) — preview as counterfactual BDD replay (what-if). Capture in wip/preview-whatif-replay-exploration.adoc. StackReference-resolves-in-preview PROVEN by a real test; one blocker = diagnostic placement (per-node invisible to StackReference). Resume cold in a fresh session.
metadata: 
  node_type: memory
  type: project
  originSessionId: 6d3faadb-1da7-486c-9310-99b6dd4c49b5
---

PARKED exploration subject, captured cold in `rke2lab/wip/preview-whatif-replay-exploration.adoc`
(indexed in `wip/README.adoc`). Likely lands on `feature/runbook-doctor` (it renegotiates a layer-3
write-side decision). EXPLORATION ONLY — no spec, no plan, no code. User wanted everything captured
before context was lost ("c'est vraiment passionnant"), then resume in a NEW conversation.

**The idea (the user's, crystallized):** the `pulumi preview` already *replays the BDD logic* to build
the next state. So make the Given/When/Then clauses feed on a *fact base* instead of deferring. The
sharp case: "dbus degraded → restart prescribed" — the operator wants to replay in dry-run with the
fact base UPDATED by the prescription's expected result ("dbus operational"), and let the BDD logic
propagate along the `dependsOn` edge to show what the remediation unblocks downstream and what stays
broken. This is a **counterfactual replay (what-if)**, NOT a "reconstruction". Reconstruction is
SUBSUMED = the what-if with zero hypotheses (degenerate case). Makes a Prescription testable BEFORE
administration → the missing link of the Remediator tier ([[doctor-remediation-model]]).

**Why it's cheap to build:** the `When` step does NOT probe inline — it calls an *injected* `probe`
(SystemdAdapterProbe/ClusterReadinessProbe = functional interfaces). The stage already injects
different impls (production=live, simulated=canned, skipped=deferred-preview). The what-if is just a
FOURTH probe source: read last-real outputs ⊕ hypotheses → return a Dossier. Given/When/Then text
unchanged. The propagation engine ALSO already exists: the `@NestedSteps` dependency replay — flip
the upstream fact to ok and the previously-SKIPPED downstream phases become evaluable. It *exercises*
the "edge decides" model.

**PROVEN by a real test (not inference):** the whole thing rests on "can a program read its own
previous outputs in dry-run?". Tested with throwaway YAML stacks on a local file backend in /tmp
(master untouched, fixture destroyed): a `pulumi:pulumi:StackReference` does a *Read* and the
referenced stack's outputs resolve to KNOWN values during `pulumi preview` (not unknown). So a stack
self-referencing reads its last-`up` outputs in its own preview. Pulumi-java 1.0.0 `StackReference`
reads ONLY top-level `outputs` (the ctx.export map) — no API to a child resource's outputs (read from
source).

**THE ONE BLOCKER = diagnostic placement (now a FACT, not a guess).** The shipped layer-3 write-side
serializes the ConsultationReport PER-NODE under each ComponentResource (recadrage a8c37c75/4e8e71d1)
— deliberately NOT top-level, to keep the Stage-B contract byte-identical + avoid phantom diff. But
StackReference reads ONLY top-level → the per-node diagnostic is INVISIBLE to it. Frontal tension:
the recadrage optimized "don't pollute the external contract" ASSUMING NO CONSUMER; the what-if IS
that consumer. Renegotiation (not contradiction): add a top-level ADDITIVE channel (e.g. a
`medicalRecord` via ctx.export, readable by a self-StackReference) in addition to / instead of
per-node. **User chose to capture first, decide this placement COLD next session.** Everything hard
(injected-probe seam, nested-edge propagation) already exists — placement is the only blocker.

**Open tensions named (not resolved):** (1) placement top-level-additive vs per-node [the blocker];
(2) semantics = counterfactual, MUST render as clearly COUNTERFACTUAL (a simulation, not a guarantee
— risk operator reads it as a promise); (3) could the Prescription carry "expected state after
administration"?; (4) hypothesis shape = boolean flip vs rich hypothetical Dossier; (5) SEPARATE
subject surfaced — should preview re-probe (probes are reads, no side effects) instead of defer?
Distinct from what-if, noted so it's not conflated.

**Next step when picked up:** decide placement → prototype a WhatIfProbe over a self-StackReference +
confirm nested-edge propagation end-to-end → only then spec→plan. Relates to [[runbook-doctor-state]]
(layer-3 write-side it renegotiates) and [[doctor-remediation-model]] (the Remediator tier it serves).
