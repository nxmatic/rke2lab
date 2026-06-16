# claude-hub — shared git subtree

The hub owns this tree (the `claude-hub` repo's `.claude/`). Each consumer repo (e.g.
`rke2lab`) carries a **squashed subtree copy** at `<repo>/.claude/hub/`. The link is
**bidirectional**: a consumer can both receive hub changes (sync down) and originate hub
changes and publish them (sync up).

## Mental model (who owns what)

- **Source of truth** = the `claude-hub` repo's **origin** (`github.com/nxmatic/claude-hub`).
  Everything converges there.
- **`<repo>/.claude/hub/`** (the subtree, e.g. in `rke2lab.d/main`) is where you normally **edit**
  hub content during a work session. Edits here are published *up* to the hub origin via
  `subtree split` (Sync up).
- **`claude-hub.d/main`** is just *a checkout* of the hub repo. You **may** edit it directly — but
  then those edits **must be made available by an immediate `git push origin main`**. This is the
  mirror image of our case: a direct hub edit that is not pushed leaves every consumer pulling a
  split branch that is behind the real hub (exactly the drift that bites later). Treat "edited the
  hub directly" and "pushed the hub" as one atomic step.

So propagation runs **both** ways through the hub origin, and the unbreakable rule is *publish
immediately*:

- consumer edit → `subtree split`/push **up** → hub origin → other consumers pull **down**;
- direct hub edit → `push origin main` **immediately** → consumers pull **down**.

## ⚠️ Session discipline — sync at BOTH ends (do not skip)

Hub drift is silent and bites later. Bracket every work session that *might* touch hub content:

### At session start

1. Confirm `claude-hub.d/main` has **no unpushed commits** (`git -C <hub> status -sb`,
   `git -C <hub> rev-list --count origin/main..HEAD`). Unpushed hub commits = an error to fix
   (verify + `git -C <hub> push origin main`) **before** anything else — otherwise the split
   branch you pull from is behind the real hub.
2. **Sync down** into the consumer so you build on the current hub, not a stale squash
   (see *Sync down* below).

### At session end, before merging the consumer branch

1. **Sync up** any hub edits you made (see *Sync up* below), so the hub origin carries them.
2. Then finish/merge the consumer branch as usual.

Keep `--squash` consistent in **both** directions, always.

## Sync down (claude-hub → this repo)

Run the split+push in the hub (regenerates the down branch from the hub's *current* state), then
pull the squash into the consumer:

```bash
git -C <hub> subtree split --prefix=.claude --branch=split/claude-hub/dot-claude --rejoin HEAD
git -C <hub> push origin split/claude-hub/dot-claude        # publish the down branch
git -C <hub> push origin main                               # --rejoin adds a merge commit; push it too
git fetch claude-hub split/claude-hub/dot-claude
git subtree pull --prefix=.claude/hub claude-hub split/claude-hub/dot-claude --squash
```

If the squash bases diverged you may get a conflict (typically because the consumer copy was
behind). The hub side is canonical for shared files — resolve `--theirs` for those, unless you
have genuine local consumer-only edits to preserve. Then `git commit` the merge.

## Sync up (this repo → claude-hub)

```bash
git subtree split --prefix=.claude/hub --branch=split/rke2lab/dot-claude --rejoin HEAD
git push origin main                                        # --rejoin adds a merge commit; push it too
git push claude-hub split/rke2lab/dot-claude
git -C <hub> subtree pull --prefix=.claude origin split/rke2lab/dot-claude --squash
git -C <hub> push origin main                               # publish the synced-up hub
```

(Replace `rke2lab` with the actual consumer repo name in the up-branch.)

## Editing rules (so the flow stays clean)

- **Edit hub content only in a consumer subtree** (`<repo>/.claude/hub/…`), never directly in
  `claude-hub.d/main`. The standalone hub checkout is pull-only.
- **Project-specific memory** lives in the consumer's **own** `.claude/memory/` (e.g.
  `rke2lab.d/main/.claude/memory/`), referenced cross-repo as `[[rke2lab:name]]`. **Hub
  (cross-cutting) memory** lives in `.claude/hub/memory/`, referenced as `[[name]]` or
  `[[hub:name]]`. Put a fact in the layer that matches its scope; don't duplicate it across both.
- One worktree per conversation, under `<repo>.d/<namespace>/<branch>` (NOT `.claude/worktrees/`).
  See the hub memory note `external-worktree-operating-model-state` for the full operating model,
  including worktree cleanup at merge time.
