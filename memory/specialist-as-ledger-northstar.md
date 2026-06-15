---
name: specialist-as-ledger-northstar
description: "North-star reframe (user, 2026-06-14): a knowledge-ACCUMULATING specialist IS a ledger — its memory is a dedicated Pulumi stack, consulting it = folding that stack's history, recruiting it = standing up a stack + StackReference edge. Converges the doctor with the federated/docrepo vision (clinic = graph of specialist-stacks). BUT: true for a SUBCLASS (accumulating specialists like drift/intervention), NOT all (DbusTcp/Network/Cluster are stateless reactive functions). One instance ≠ a pattern — build the drift specialist as the FIRST ledger-backed specialist, let a 2nd reveal whether to extract a general abstraction."
metadata: 
  node_type: memory
  type: project
  originSessionId: 6fa6b30b-f578-4ee4-9ffa-806a1172c020
---

**THE REFRAME (user, during the intervention-provenance chantier).** "A ledger looks a lot like
recruiting a specialist. Maybe specialists are themselves ledgers, each with a dedicated stack;
recruiting = standing up a stack." This dissolves the apparent tension between "the intervention-ledger"
and "the doctor": the ledger was never a persistence layer beside the doctor — it is **the MEMORY of a
specialist**.

**WHY IT FITS the drift/intervention specialist exactly.** Domain = interventions across the external
boundary; memory = the ledger stack of accumulated interventions (declared + detected); consult = fold
its history + join by time (`InterventionLedger.between(from,to)` already exists for this); it is GATED
ON AN EXTERNAL ACTION (the "specialist that needs an external action" the user named). So the work
already done is recast cleanly: `StackCoordinate` = how the doctor references a specialist's stack;
the dedicated stack = the specialist's memory; recruiting = a StackReference edge to a new stack.

**CONVERGENCE.** This is the SAME shape as the federated vision: [[docrepo-dag-state]] ("each app = a
repo for neighbours", units = git trees, OSGi-resolution + p2p) and [[rke2lab:serviceloader-specialist-spi]]
(roster = extension point; "recruit a specialist" = publish a bundle). The doctor's clinic = a GRAPH OF
SPECIALIST-STACKS, StackReference = the edges. Two of the user's threads (the doctor, docrepo) are the
same architecture seen from two ends.

**★ THE EPISTEMIC GROUNDING (user, 2026-06-14 — this is what makes the reframe SOUND, not just neat).**
TWO MEMORIES, two epistemic scopes, COMPLEMENTARY not competing:
- *The GENERALIST's memory = the medical record* (folded over seed-master's stack history). Its scope
  is bounded by WHAT SEED-MASTER CAN OBSERVE OF ITSELF: what it saw in-run, what it prescribed, what it
  expected. This is the synthesis the generalist keeps for the patient's future treatment.
- *A SPECIALIST's memory = its own timeline/stack* of events in its domain that fall OUTSIDE seed-master's
  field of view (e.g. the operator's out-of-band `nft delete` — seed-master cannot know it). The drift
  specialist KNOWS things seed-master can't. The seed-master stack boundary IS the boundary of its
  self-observation; beyond it, specialists hold the domain knowledge. **seed-master cannot know everything
  — that is natural, not a defect.**
Consultation JOINS the two across the boundary: at the generalist's final synthesis it notices a gap in
ITS bounded memory ("I expected MY prescription to cure it, yet it resolved without it") — which by
construction it cannot explain alone — so it REFERS to the specialist, who reads ITS OWN chronology (the
intervention ledger) and returns a LETTER ("an external intervention cured it; your prescription is
confounded"). The generalist integrates the letter into the record; it does NOT absorb the specialist's
timeline, it receives a synthesis (a return letter).

**THE DISCIPLINE GUARD.** "ALL specialists are ledgers" over-generalizes from ONE instance (CLAUDE.md
rule-of-three). The two kinds are NOT arbitrary — the split follows WHERE THE DOMAIN KNOWLEDGE LIVES:
- *reactive / stateless* — domain is entirely WITHIN the in-run observation (DbusTcp, Network, Cluster).
  No stack: nothing to remember beyond the current Referral.
- *knowledge-accumulating / ledger-backed* — domain CONTAINS events outside seed-master's field (drift/
  intervention; maybe a future efficacy specialist). A dedicated stack/timeline is natural because the
  knowledge cannot come from self-observation.
"Specialist = ledger = stack" holds for the subclass whose DOMAIN IS THE OUTSIDE. One concrete instance.

**THE MULTI-TIME CONSULTATION MODEL (user, 2026-06-14 — re-derived the deferred agenda loop from real
medical practice).** A real generalist↔specialist consultation is NOT one synchronous shot (our current
`Generalist.consult(symptom, observation)` is the simplification): it is (1) generalist refers → (2)
SEPARATE specialist consultations at different times → (3) each specialist prescribes + writes a RETURN
LETTER (= today's `ReferralReply`) → (4) a FINAL SYNTHESIS consultation back at the generalist, who
integrates the prescriptions + letters → (5) the generalist KEEPS the synthesis in memory for future
treatment. This IS the "agenda loop" deferred in [[rke2lab:referral-roundtrip-state]]. The drift trigger falls
out naturally: it is the generalist's FINAL-SYNTHESIS step noticing expected-vs-observed drift (symptom
present @N, gone @N+1, but MY prescription was the expectation) and referring to the drift specialist —
NOT a bolted-on second consultation mode, and NOT a synthetic "symptom" for a success. The Increment-B
`Expectation` is exactly "what the generalist retained expecting."

**THE PLAN THAT RESPECTS ALL THIS.** Build the drift/intervention specialist as the FIRST ledger-backed
specialist — its own stack (proven per-resource-output + history-fold, [[model-substrate-alignment]]),
referenced via `StackCoordinate`, consulted by folding + time-join — invoked from the generalist's
final-synthesis step. Generalist memory stays the medical record (already folded). Keeps the cross-stack
capability the user wants, instantiates the north-star CONCRETELY, no premature `LedgerBackedSpecialist`
abstraction (extract only if a 2nd appears). See [[rke2lab:intervention-provenance-state]] for the chantier.
