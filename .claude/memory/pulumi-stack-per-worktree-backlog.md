---
name: pulumi-stack-per-worktree-backlog
description: "BACKLOG / brainstorm-later: how to manage Pulumi stacks per git worktree, since the flox backend URL is project-relative so every worktree gets an empty state."
metadata: 
  node_type: memory
  type: project
  originSessionId: 321da8b5-a82b-4af2-8f71-460186de7864
---

**★ BACKLOG, surfaced 2026-06-14 — brainstorm BEFORE building.** Decide how a worktree should get a usable Pulumi stack.

**Root mechanism:** the flox manifest sets `PULUMI_BACKEND_URL='file://${FLOX_ENV_PROJECT}/.pulumi-state'` (`.flox/env/manifest.toml:56`), and `FLOX_ENV_PROJECT` = the activated project root = the worktree dir. So:
- Main checkout → `/rke2lab/.pulumi-state/` → holds the REAL `dev` stack (+ `noexport-test`, `sandbox-selfread`, history, backups).
- Any `.claude/worktrees/<x>/` → its own `.pulumi-state/`, created empty by the `on-activate` `mkdir -p`. `pulumi stack ls` there = empty.

**The tension (this is the crux):** user asked "shouldn't a worktree import the dev stack at init?" — but a naive import RE-CREATES the cross-session sharing that the [[worktree-per-conversation]] rule just eliminated. Two worktrees each holding a `dev` that points at the SAME real master = divergent states + colliding `pulumi up` on one infra. Must split two needs:
- **Mutate real dev infra** → stays at the MAIN checkout only (single source of truth for dev state). A worktree should NOT `up` dev.
- **Preview/test the worktree's code against realistic data** → either a throwaway stack (proven safe this session: isolated worktree-local backend, `pulumi preview` exercised the full runtime path) OR a READ-ONLY frozen snapshot of dev (export→import a copy, never written back to the real master).

Rule of thumb: **copy state to READ it, never to re-write it to the same infra.**

**Options on the table (undecided):** (a) on-activate imports a read-only frozen dev snapshot; (b) point worktree `PULUMI_BACKEND_URL` at the main checkout's `.pulumi-state` (simple but reintroduces shared lock — risky); (c) status quo: empty state, throwaway stack for previews, real dev work at main checkout. Also in scope when brainstorming: stack lock, divergence, secrets/passphrase (`PULUMI_CONFIG_PASSPHRASE` empty in manifest; dev has `encryptionsalt`), and the nix deploy path (`nix build .#seed-master`).

See [[worktree-per-conversation]] (the isolation rule this must not violate) and [[preview-whatif-topic]] (self-referential state read patterns already explored).
