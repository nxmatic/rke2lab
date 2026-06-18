---
name: merge-from-target-worktree
description: "Integrate a finished sub-branch with a SQUASH merge (one commit on the target), run FROM the worktree that owns the target branch — never advance another branch's HEAD from your own worktree. Two near-misses caught by the user on 2026-06-18: almost merged from the wrong worktree, and assumed ff instead of squash."
metadata:
  node_type: memory
  type: feedback
---

When a feature/refactor sub-branch is done and ready to fold into its parent (here:
`refactor/layout-skeleton` → `design/target-module-layout`), two things are fixed:

**1. It is a SQUASH merge, not a fast-forward.** We want ONE commit on the target branch per integrated
sub-branch, regardless of how many commits the sub-branch accumulated (here: the refactor + a memory
note = 2 → collapse to 1). So the integrating gesture is `git merge --squash <sub-branch>` then a single
`git commit`, NOT `--ff-only`. The sub-branch's individual commits stay as working history on the
sub-branch; only the squashed summary lands on the target. Pairs with [[rke2lab-solo-no-pr-merge-direct]]
(the *what* — direct merge, no PR) — this is the *how*.

**2. Run it FROM the worktree that owns the TARGET branch.** Under the external-worktree operating model
every branch has its own checkout (`<repo>.d/<ns>/<branch>`). Advancing the target branch's HEAD from a
different worktree mutates a branch another live workspace is sitting on — exactly the cross-worktree
collision the isolation rule forbids (see [[sops-worktree-smudge-noise]] and hub
`external-worktree-operating-model-state`). Prep (commit, build-green, verify) in the sub-branch worktree
is fine; the `git merge --squash` + commit + worktree/branch teardown are the TARGET workspace's gesture.

**How to apply:** in the sub-branch worktree, finish + commit + build-green + verify
(`git log <target>..<sub>` shows the commits about to be squashed). Then STOP and hand off: state the
commands the target workspace runs (`git merge --squash <sub>` ; `git commit` ; then remove worktree +
delete branch, AFTER the merge). Do NOT run them from here.

**Corollary — commit everything BEFORE close/teardown.** The working tree is ephemeral; only what is
committed onto the sub-branch survives to be squashed. Anything worth keeping (code AND session memory:
state files, MEMORY.md index lines, feedback notes) must be committed to the sub-branch first — memory is
NOT exempt because it lives under `.claude/memory/`, it rides the same branch into the squash. Check
`git status` is clean as the last gesture before handing off. (User reminders 2026-06-18: "si on close,
on doit toujours tout committer ce qu'on veut sur la branche" and "on veut un seul commit au merge".)
