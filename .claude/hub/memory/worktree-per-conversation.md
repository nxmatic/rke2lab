---
name: worktree-per-conversation
description: "Each Claude conversation gets its OWN git worktree; the main checkout stays on main, read-only. Never share a worktree between parallel conversations."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a6f17f6c-4d13-432b-9cc0-1e4239a9efcf
---

**Convention set 2026-06-14, PROMOTED to a global rule (`~/.claude/CLAUDE.md` → "Workspace isolation") same day.** Never share a git worktree (or branch checkout) between parallel Claude conversations. Each conversation works in its OWN worktree under `.claude/worktrees/`; the **main checkout stays on `main`, read-only**. The CLAUDE.md rule is now the authoritative statement (highest priority, loaded every session); this note carries the *why* and the surviving per-repo gotchas.

**Trigger refined (option 1):** enter the dedicated worktree only when a task is about to **mutate** files — pure read-only/investigation stays in the current checkout. Coherence established the same day: `main` is kept fast-forwarded and pushed to `origin`, so a `fresh` worktree (branches from `origin/<default>`) always starts from current work.

**Why:** During the rke2lab wip-guard migration, the main checkout was sitting on another session's branch (`improve/operator-intervention-provenance`) with their uncommitted work (pom.xml, manifest.lock churn). My migration kept entangling with theirs: a `flox` re-lock re-pinned the self-referential `maven` flake (~118 lines), I had to rebase onto a mid-flight maven-plugins merge on main, and re-locking failed in the worktree because the `[include]` `../fleet/flox/git` is a path relative to the manifest and doesn't resolve from `.claude/worktrees/…`.

**How to apply:**
- Start every task by entering a dedicated worktree (EnterWorktree, default `baseRef: fresh` = branches from origin/<default>) — gives a clean base off main, isolated from other sessions' dirty trees.
- Keep the main checkout on main; treat it as read-only reference, not a workspace.
- Gotcha that survives: flox `[include]` uses manifest-relative paths (`../fleet/…`), so `flox activate`/re-lock can't compose includes from inside a worktree. If a flox re-lock is needed, do it where `../fleet` resolves (the main checkout on main), or edit `manifest.lock` surgically. The lock has NO integrity hash — a targeted JSON edit (load, mutate the `on-activate` strings, `json.dump(indent=2, ensure_ascii=False)`) is safe and yielded a clean 4-line diff.

See [[global-wip-guard-hooks-state]] (the migration this lesson came from) and [[branch-namespaces]].
