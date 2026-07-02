---
name: superpowers-assets-in-wip
description: "Working plans + specs live in wip/ (flat: wip/plans/, wip/specs/), NEVER docs/. docs/ is for DURABLE artifacts only (design, atlas, glossary, proof records). The user does not care about 'superpowers' — that is just a tool whose default dir is wrong; the rule is about wip/ vs docs/."
metadata:
  node_type: memory
  type: feedback
---

**The rule (the user's convention, tool-agnostic):** a working *plan* goes in **`wip/plans/<date>-<topic>.md`**;
a working *spec* goes in **`wip/specs/<date>-<topic>.adoc`**. FLAT by kind, no nesting. `docs/` is for
DURABLE artifacts only — design docs, the integration atlas, the glossary, proof records: the things that
outlive a chantier and get cross-referenced. The user is indifferent to "superpowers"; do NOT create a
`superpowers/` subfolder anywhere (neither `docs/superpowers/` nor `wip/superpowers/`).

**Why:** `wip/` is the throwaway/work-in-progress zone the wip-guard blocks from reaching `main` (see
[[wip-guard-hooks]]). Plans/specs are scaffolding for a chantier — they guide execution, then are
typically removed (per [[rke2lab:medical-record-impl-complete]]). Putting them in `wip/` means they
(a) live as working artifacts on the branch, (b) can never accidentally land on main, (c) keep `docs/`
clean for the durable set.

**How to apply:** whatever generates a plan/spec (the superpowers `writing-plans`/`brainstorming` skills
default to `docs/superpowers/…` — WRONG), write it to `wip/plans/` or `wip/specs/` directly. As of
2026-07-02 `docs/superpowers/` is **gitignored** so that default can never re-land in the tree — the
barrier, not vigilance.

**The recurring drift (why this memory exists) — the WRONG place each time was `docs/superpowers/`:**
- 2026-06-11 — HealthSystem keystone: plan landed in the skill default (docs/superpowers/plans/), removed before squash-merge.
- 2026-06-16 — walker-retirement spec: landed in the skill default (docs/superpowers/specs/) again, moved to wip/.
- 2026-07-02 — I (Claude) put a pipeline plan in the skill default AND spoke of a `wip/superpowers/` nesting;
  the user: "je me fiche de superpowers." Corrected: all 5 stray skill-default files (4 plans + 1
  spec) moved to flat `wip/plans/` + `wip/specs/`, the lone `wip/superpowers/plans/` file repatriated,
  `wip/superpowers/` removed, the skill-default dir gitignored, this memory realigned to the flat rule.

**★ BACKLOG (naming):** the user PREFERS `.wip/` (dotted, the docrepo convention that strips at
squash-merge), but the rke2lab wip-guard hooks currently match only `wip/` (non-dotted). Use **`wip/`**
now; converge `wip/` → `.wip/` once the guard learns the dotted form (see [[global-wip-guard-hooks-state]]).
NB rke2lab has no `.githooks/` wip-guard in-tree yet — keeping plans/specs off `main` is MANUAL vigilance
at merge time until the global-wip-guard chantier wires it here.
