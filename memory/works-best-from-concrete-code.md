---
name: works-best-from-concrete-code
description: "Working-style: the user reacts and decides best when seeing concrete implementation, not abstract design — favour showing code/impl early, expect design refinements during execution"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

The user stated (2026-06-08) that it is *easier for them to react when they see me implement* than from
abstract design alone. Confirmed in practice: during the medical-record plan execution, the most
important design decisions (StackDeployment delegation via fromJson, the renamings, and the whole
layered error-handling contract Optional-vs-throw + partial+suppressed) surfaced ONLY once we started
EXECUTING task-by-task and they saw concrete code — not during the pure design/plan phase.

**Why:** the user thinks against concrete artifacts. Abstract C4/UML design is necessary and they DO
review it (diagrams not Java — [[docs-diagrams-not-java]]), and our design+plan quality was high enough
that the execution-time changes were *refinements, not rewrites*. But the sharpest, most decisive
feedback comes when there is real code to point at.

**How to apply:** this VALIDATES subagent-driven execution for this user — each task yields concrete
code, they react, we refine, I record the decision. So: do not over-polish design in the abstract
before showing something runnable; get to a concrete first cut quickly, then expect and welcome
mid-execution design corrections (renames, contracts, signatures). When a correction lands, propagate it
back into the plan + memory immediately (as done for the error contract), so the plan stays the source
of truth. Keep narrating each subagent step ([[working-style-narrate-progress]]) — the user is anxious
with subagents and reassured by a concrete play-by-play.
