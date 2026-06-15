---
name: sequential-no-compat-workflow
description: "Single dev, strictly sequential topics — never write backwards-compat code; delete old paths in the same change, system must be fully on the new path once merged"
metadata:
  type: feedback
---

The user is the **sole developer** and works **one topic at a time, strictly sequentially** —
only one feature/refactor is ever in flight, and there are no other consumers of the code.

**Rule:** never be conservative when introducing a feature. No backwards-compatibility shims,
no parallel old+new code paths, no deprecation periods, no "dead weight, remove later." When a
refactor supersedes an old path (function, branch, delivery mechanism, config knob), delete the
old one and update all call sites **in the same change**. Once a topic merges, the system works
**fully** on the new path with **no old paths remaining**.

**Why:** single-developer + sequential means there is never a second consumer to break, so the
usual reasons for compatibility (staged rollout, external callers, parallel branches) don't
exist. Carrying old paths is pure cost — review noise, cognitive load, refactor drag.

**How to apply:**
- Default to deleting old code immediately; don't ask "keep it for compat?" — the answer is no.
- This reinforces the global CLAUDE.md "never leave dead code" rule and the project's
  "Uniformity enforcement" / "no deprecation warnings" conventions.
- Combine with [[rke2lab:config-restructuring-state]]'s preview-only fact: on feature branches nothing
  deploys (only `pulumi preview` tests), so there's also no running deployment to keep green
  *between* commits — the branch only needs coherence when the topic is done.
- Plans should delete superseded APIs in the same task that introduces the replacement (as the
  config migration plan does), not in a later "cleanup" pass.
