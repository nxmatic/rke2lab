---
name: worktree-claude-session-symlink
description: "How to make a Claude conversation started from rke2lab.d/main appear in the session list of an external worktree's VSCode window — symlink the .jsonl under the worktree's project slug + a .code-workspace named after the branch in the parent rke2lab.d/. PROVEN working 2026-06-17."
metadata: 
  node_type: memory
  type: reference
  originSessionId: afb438a9-19b8-4a63-8b3a-6e4502f4ac76
---

In the external-worktree operating model ([[external-worktree-operating-model-state]]), the rule is
"one VSCode window = the worktree's code + its Claude chat". But conversations are almost always
STARTED from `rke2lab.d/main`, so Claude files their transcript under main's project slug — a window
opened on the worktree won't see the chat. Two pieces fix this; both PROVEN working 2026-06-17 on
branch `refactor/config-extender`:

**1. Symlink the transcript under the worktree's project slug.** Claude keys sessions by the
launch directory, encoded as a slug in `~/.claude/projects/` (absolute path, every `/` → `-`). So
`rke2lab.d/refactor/config-extender` → slug `-private-var-lib-git-nxmatic-rke2lab-d-refactor-config-extender`.
Identify OUR transcript in main's project dir by content (not mtime — BSD `stat -f` differs), e.g.
`grep -c 'refactor/config-extender'` and a unique marker like `osgi-bench-testkit` across the
`*.jsonl`; the one with the most hits + most lines is the live one. Then:
```
SRC=~/.claude/projects/-private-var-lib-git-nxmatic-rke2lab-d-main
DST=~/.claude/projects/-private-var-lib-git-nxmatic-rke2lab-d-refactor-config-extender
mkdir -p "$DST"
ln -sf "$SRC/<session-uuid>.jsonl" "$DST/<session-uuid>.jsonl"
```
Chirurgical (one .jsonl), not the whole dir — keeps worktree-window isolation (it only sees grafted
sessions, not all of main's). Live: the symlink reflects the growing file as the chat continues.

**2. A `.code-workspace` named after the branch, in the PARENT `rke2lab.d/`.** Not inside the
worktree (would commit onto the branch, pollutes, differs per worktree); `rke2lab.d/` itself is NOT a
git repo (verified), so it's a safe home outside any tracking. File
`rke2lab.d/refactor-config-extender.code-workspace` with one folder = `refactor/config-extender` and
the flox JDK 25 runtime. Opening it makes the window's first folder the worktree → Claude resolves
the worktree slug → the symlinked session appears.

`.code-workspace` content (adapt branch name + the flox run path's arch slug per machine):
```json
{
  "folders": [{ "name": "refactor/config-extender", "path": "refactor/config-extender" }],
  "settings": {
    "java.import.maven.enabled": true,
    "java.configuration.runtimes": [
      { "name": "JavaSE-25", "path": "${workspaceFolder}/.flox/run/aarch64-darwin.rke2lab.dev", "default": true }
    ]
  }
}
```

**Order matters:** do the symlink BEFORE expecting the workspace to show the chat — a `.code-workspace`
made before the graft shows nothing (the chat still lives only under main's slug). **Open caveat (not
yet tested):** if you RESUME the conversation from the worktree window, unverified whether Claude keeps
writing the same file (via the link) or starts a new one under the worktree slug. The real file lives
under main either way.

**END-OF-WORK MERGE RITUAL (the creator is the gravedigger).** A worktree session must NOT squash-merge
or delete its own worktree — it would saw off the branch it sits on, and the worktree's lifecycle belongs
to the checkout that CREATED it (`main`). So when branch work is done: (1) the worktree session finishes —
code + memory committed ON THE BRANCH, build+test green; (2) CLOSE the worktree window; (3) switch to the
`main` window; (4) RESUME this conversation there (it's natively visible under main's slug — the real
`.jsonl` lives there) and say "merge/squash the worktree"; (5) the `main` session — owner of the worktree —
runs the squash-merge, push origin/main, then `git worktree remove` + `git branch -d` (+ delete the
orphaned session symlink under the worktree's slug). Memory is BRANCH work (committed on the branch, per
the leak fix above [[track-claude-memory-in-repo]] symlinks home memory → main's checkout, so writing from
a branch session lands in main's working tree — copy into the branch worktree + commit on the branch
instead); it reaches main via the merge, not by a direct write to main.

See [[external-worktree-operating-model-state]], [[step2-decomposition-state]], [[rke2lab-solo-no-pr-merge-direct]].
