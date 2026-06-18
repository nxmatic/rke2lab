---
name: merge-from-target-worktree
description: "Integrate a finished sub-branch with a SQUASH merge (one commit on the target), executed FROM the worktree that owns the TARGET branch (= where Claude already sits when it owns this work) — Claude DOES the merge + teardown itself, it does NOT ask permission. The only constraint is the LOCATION: never run the squash from the SUB-branch's own worktree (that's what advances another branch's HEAD). The integration-status memory line goes INSIDE the merge commit (amend before push), not a follow-up commit, and must not cite that commit's own hash. Near-misses caught by the user 2026-06-18: merged from the wrong worktree, assumed ff instead of squash, recorded 'merged' in a trailing commit instead of amending — and (the OVER-correction to delete) wrongly hardened this into 'the merge is the human's, hand off', which made Claude re-ask permission for a merge it created and may do itself."
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

**2. Run it FROM the worktree that owns the TARGET branch — Claude does it, no hand-off.** This is a rule
about LOCATION, not about WHO. When Claude owns the chantier (it created the sub-branch, worktree and
workspace), it ALSO does the squash-merge and the teardown itself — it must NOT ask the user to click a
permission for it. The single constraint: execute from the worktree that owns the TARGET branch (e.g.
`design/…`, where Claude usually already sits), NOT from the sub-branch's own worktree. Why the location
matters: under the external-worktree model every branch has its own checkout (`<repo>.d/<ns>/<branch>`),
and advancing the target's HEAD from a *different* live worktree mutates a branch another workspace sits
on — the cross-worktree collision the isolation rule forbids (see [[sops-worktree-smudge-noise]], hub
`external-worktree-operating-model-state`). So: prep/commit/build-green on the sub-branch; then from the
TARGET worktree run `git merge --squash <sub>` → `git commit` → teardown. (Do NOT over-correct this into
"the merge is the human's" — that was a wrong hardening that re-introduced permission friction; the merge
of Claude's own sub-branch is Claude's to perform. Genuine hand-off is only the runtime boundary in
[[standing-autonomy-except-runtime-config]].)

**How to apply:** finish + commit + build-green + verify on the sub-branch (`git log <target>..<sub>`
shows the commits about to be squashed; `git status` clean). Then, FROM the target worktree:
`git merge --squash <sub>` ; `git commit` (amend the integration-status line in) ; remove the worktree +
delete the branch. All of it Claude's gesture when Claude owns the work.

**3. The integration-status line belongs INSIDE the merge commit — amend, don't append.** A memory
index line that flips a chantier from "awaiting merge" to "shipped/merged" is part of the integration,
so it rides the SAME commit as the squash, added via `git commit --amend` while that commit is still
unpushed — NOT a separate trailing "docs(memory): mark X merged" commit. Reason (the lesson from the
bnd-annotations spike, now generalized): a commit cannot truthfully declare its own merge, and a
follow-up commit just to set status is noise. **Corollary — never cite the merge commit's own hash in
that line**, because the amend changes the hash → the reference goes stale instantly (same
self-reference trap). Write the status hash-free (`SHIPPED to <target> (squash merge <date>)`); the
git history already carries the hash. (User, 2026-06-18: "tu devrais modifier le statut avant de merge,
ce serait encore plus propre.")

**Corollary — commit everything BEFORE close/teardown.** The working tree is ephemeral; only what is
committed onto the sub-branch survives to be squashed. Anything worth keeping (code AND session memory:
state files, MEMORY.md index lines, feedback notes) must be committed to the sub-branch first — memory is
NOT exempt because it lives under `.claude/memory/`, it rides the same branch into the squash. Check
`git status` is clean as the last gesture before handing off. (User reminders 2026-06-18: "si on close,
on doit toujours tout committer ce qu'on veut sur la branche" and "on veut un seul commit au merge".)
