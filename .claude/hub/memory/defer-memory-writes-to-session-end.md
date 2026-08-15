---
name: defer-memory-writes-to-session-end
description: "User preference (2026-06-17): do NOT write auto-memory mid-session — it mutates the main checkout while feature work is in flight (the home memory symlink points at <repo>.d/main/.claude/memory, so every memory write lands in main regardless of the active worktree). Instead, accumulate what to remember and flush it ALL at session end in one commit+push to main."
metadata:
  node_type: memory
  type: feedback
---

The user dislikes memory writes landing in `main` *during* feature work. The mechanism: the home memory
symlink (`~/.claude/projects/<slug>/memory`) points at `<repo>.d/main/.claude/memory`, so ANY memory
write goes to the `main` checkout — never to the active feature worktree — and shows up as uncommitted
churn in `main` while branches are in flight.

**Why:** keeping `main` clean during the session is the user's preference; mid-session memory edits make
`main`'s working tree dirty for reasons unrelated to the merge in progress.

**How to apply:** during a session, ACCUMULATE the things worth remembering (in the running narration /
todo), and do NOT write memory files as you go. At SESSION END, write + update them all in one pass, then
commit + push to `origin/main` in a single gesture. This composes with the start-of-session reconcile
gate in [[external-worktree-operating-model-state]] (the "uncommitted memory desyncs the next session"
failure mode): defer to the end, but DO flush before stopping — an unflushed memory is as bad as a
mid-session one. Accepted trade-off: if the session is cut before the end-flush, the memory is lost
(deemed minor). NOTE: memory is repo-wide meta-state, not branch code — it correctly belongs in `main`,
not in feature branches; the preference is about TIMING (end, not mid), not LOCATION.
