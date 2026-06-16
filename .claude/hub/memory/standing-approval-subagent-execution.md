---
name: standing-approval-subagent-execution
description: User pre-approves subagent-driven execution of implementation plans — no need to ask each time which approach
metadata:
  type: feedback
---

When a written implementation plan reaches the execution-handoff step, the user
has a STANDING preference for the **subagent-driven** approach (fresh subagent per
task + review between tasks). Do not re-ask "subagent-driven vs inline" each time —
default to subagent-driven and proceed.

**Why:** The user said it explicitly — "tu contrôles très bien leur travail." They
trust the per-task dispatch + two-stage review loop and would rather skip the
recurring choice prompt.

**How to apply:**
- At the writing-plans execution-handoff, state that you're proceeding
  subagent-driven (per the standing approval) instead of asking.
- Still invoke [[superpowers]] subagent-driven-development as the sub-skill, and
  still review between tasks — the approval is about the APPROACH, not about
  skipping the reviews.
- Gates the user owns personally (e.g. fresh-session verification, branch
  integration) remain theirs — standing approval doesn't absorb those.
- This is a preference, not a lock: if a plan is genuinely tiny or the user asks
  for inline, honor that.

See [[sequential-no-compat-workflow]] (how the user likes work sequenced),
[[working-style-narrate-progress]] (narrate while subagents run).
