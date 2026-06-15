---
name: wip-guard-hooks
description: "Git hooks in .githooks/ block wip/ from reaching main; wired via core.hooksPath in flox on-activate"
metadata:
  type: reference
---

Repo-tracked git hooks guard against `wip/` ever landing on `main` (we leaked it once via a
fast-forward merge). Committed `d5d3f983` on main; on `feature/runbook-doctor` too (cherry-pick).

**Files** (`.githooks/`, tracked, shared):
- `lib-wip-guard.sh` — shared helpers (`wip_present_in`, `wip_present_in_index`, `reject`);
  PROTECTED_BRANCH=main, WIP_DIR=wip.
- `pre-commit` — blocks committing/squash-merging `wip/` onto main (checks staged index when HEAD=main).
- `pre-push` — catch-all backstop: refuses to push a `refs/heads/main` ref whose commit carries
  `wip/`. This catches the fast-forward-merge case `pre-commit` can't (ff creates no commit). Reads
  git's stdin `<local-ref> <local-sha> <remote-ref> <remote-sha>` per pushed ref.

**Activation:** hooks live in tracked `.githooks/`, NOT `.git/hooks/` (the latter is local-only,
never cloned). `core.hooksPath=.githooks` activates them — wired into the flox `[hook] on-activate`
block (`.flox/env/manifest.toml`), idempotent, so `flox activate` (run for every build) sets it per
clone. Until activate runs once, hooks are dormant.

**Boundary:** client-side only (protects local machines via flox). No server/GitHub enforcement — a
CI check or branch-protection rule would be the server-side complement if ever wanted.

**Feature branches keep `wip/`** — that's correct; the guard only fires for `main`. See
[[rke2lab:config-restructuring-state]] for the leak that motivated this.
