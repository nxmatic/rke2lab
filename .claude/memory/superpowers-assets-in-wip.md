---
name: superpowers-assets-in-wip
description: "Superpowers-generated working assets (writing-plans plans, brainstorming specs) belong in wip/, NOT docs/. wip/ is the wip-guard-protected scaffolding zone (never reaches main); docs/ is for durable artifacts (design, atlas, glossary, proof records)."
metadata:
  node_type: memory
  type: feedback
---

The superpowers `writing-plans` skill defaults to `docs/superpowers/plans/` and `brainstorming` to
`docs/superpowers/specs/`. The user's convention OVERRIDES this: those working assets go in **`wip/`**
(e.g. `wip/superpowers/plans/<date>-<topic>.md`), NOT `docs/`.

**Why:** `wip/` is the throwaway/work-in-progress zone the wip-guard (`.githooks/` pre-commit +
pre-push, see [[wip-guard-hooks]]) blocks from reaching `main`. Plans and specs are scaffolding for a
chantier — they guide execution, then are typically removed (per [[medical-record-impl-complete]],
the executed plan is not kept). Putting them in `wip/` means they (a) live as working artifacts on
the branch, (b) can never accidentally land on main, and (c) keep `docs/` clean for the DURABLE
artifacts only: design docs, the integration atlas, the glossary, proof records — the things that
outlive the chantier and get cross-referenced.

**How to apply:** when invoking writing-plans / brainstorming, write the plan/spec under
`wip/superpowers/` instead of the skill's `docs/superpowers/` default. At merge time the plan is
either removed or left in `wip/` (the squash-merge / wip-guard excludes it from main either way).

First correction: 2026-06-11, during the HealthSystem keystone chantier
([[healthsystem-keystone-state]]) — the plan was written to `docs/superpowers/plans/` and had to be
removed before the squash-merge to main.
