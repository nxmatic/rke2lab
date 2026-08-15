---
name: memory-auto-garbage
description: "RULE (user, 2026-06-21): keep the memory index BALANCED with the memory DAG by automatic garbage collection. The MEMORY.md index re-grows past its ~24KB auto-load cap as chantiers accumulate; rather than wait for a manual cure, garbage-collect routinely — the index is the live surface, the node files are the DAG, and a node whose value moved into git history or into a consolidated note is garbage to be reclaimed from the index. Named s/cleanup/garbage/ to mark it as GC, not housekeeping."
metadata:
  node_type: memory
  type: feedback
---

The user named this as a standing rule (2026-06-21), the systematic pendant of the one-off index cure
we ran by hand this session (MEMORY.md 57KB -> 20.8KB).

## The rule

Keep the index (`MEMORY.md`) BALANCED with the DAG (the node files). The index is auto-loaded and
capped (~24.4KB); it silently truncates above the cap, so entries below the cut go invisible. As
chantiers accumulate the index re-grows — so garbage-collect ROUTINELY, not only when it overflows.

It is GARBAGE COLLECTION, not tidying (hence s/cleanup/garbage/): a node or an index line is garbage
when its VALUE has moved elsewhere and the slot can be reclaimed —
- a SHIPPED slice whose how-it-was-done now lives in git history → collapse its index line to a
  one-liner pointer (the [[memory-synthesis-prune-the-how]] criterion: keep the what/why, reclaim the
  how);
- a spent resume-state / WI-done log / pre-reload resume point → delete the node, repair the edges;
- a node consolidated into a broader note → delete, repoint its `[[links]]`.

## How to apply (every memory flush, not a special pass)

- After writing/updating nodes, check the index size; if it trends toward the cap, GC the heaviest
  SHIPPED entries to one-liners (their detail is safe in the file + git).
- Reclaim implies REPAIR: a deleted node's inbound `[[links]]` must be removed or repointed (no dead
  edges). Verify zero orphans (every node indexed) and zero dead links after a GC.
- This composes with [[memory-synthesis-prune-the-how]] (the deep synthesis pass) — that one is the
  big periodic garden; THIS rule is the routine GC that keeps the index from ever needing the big one
  in a panic. Same discipline as [[migration-branch-no-fallback]] applied to memory: once a thing's
  value is reached/relocated, reclaim the slot, don't hoard it.

## Why it matters

An over-cap index is not a cosmetic problem: it TRUNCATES, so the most recently added chantiers (the
ones a session needs) can fall below the cut and vanish from the session-start context. Balancing the
index with the DAG keeps the live surface honest — itself a re-entrance instance
([[reentrance-northstar]]): the memory system obeys the same "render the live state faithfully, do not
let it drift" rule it imposes on everything else.
