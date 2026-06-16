---
name: review-scope-backlog
description: "How quality reviewers must treat pre-existing code during subagent-driven plan execution: strict-diff + separate backlog, never block or refactor out-of-scope"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

When executing a plan with subagent-driven-development, the code-quality reviewer subagents must use
**strict-diff scope + separate backlog** (user decision 2026-06-08):

- Review ONLY the lines the task added/modified (`git diff BASE..HEAD`). Do NOT judge pre-existing code.
- If the reviewer spots a genuine problem in *pre-existing* code it happens to read (the doctor's
  already-committed `controlplane/bdd/` — ConsultationReport, Symptom, RemediationProgramRef, etc.),
  it must NOT block the task and NOT request an out-of-scope refactor. Instead it **collects** the smell
  into a backlog list.
- I (the controller) accumulate those backlog items across tasks and **present the full list to the
  user at the END of execution**, so they can decide a SEPARATE cleanup topic.

**Why:** the user's "delete the legacy / absolute uniformity" rules are real, but applying them inline
would balloon scope task-by-task. Capturing them as a dedicated backlog honours the rule without
derailing the plan in flight. The user explicitly anticipated the quality reviewer would be unhappy
with existing doctor code that new tasks (7/8/10/11) sit next to.

**How to apply:** every code-quality-reviewer prompt gets a clause: "Review scope = the diff only;
pre-existing code is out of scope — note any real smell as a non-blocking backlog item, never request
its refactor." Relates to [[rke2lab:medical-record-query-api-state]] and [[sequential-no-compat-workflow]].
