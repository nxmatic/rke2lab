---
name: workspace-driven-by-need
description: "VSCode workspaces are composed by the NEED (the active chantier), not by a fixed domain taxonomy. A workspace = the repos a concrete task touches; it lives and dies with the chantier. claude-hub is always the root (hub auto-loads)."
metadata:
  node_type: memory
  type: feedback
---

**The need dictates the workspace** (decided 2026-06-14, after trying and rejecting a domain taxonomy).

We explored grouping repos by stable DOMAIN (maven-tooling, k8s-infra, darwin-host, networking…). It was rejected: the user's needs are always multiple and cross-cutting, so fixed domain boundaries don't hold. Example: `fleet` ended up inside the rke2lab workspace because at one point the user was tuning flox `manifest.toml` files in VSCode *while* working on the management-cluster bootstrap — a concrete combined need, not a domain.

**The model:**
- A workspace = the repos a concrete chantier combines. It's composed on demand and is ephemeral (born with the chantier, pruned when done).
- `claude-hub` is the root folder of every workspace → the hub auto-loads as the memory entry point.
- A few DURABLE workspaces are fine for combos reopened often, but they're the exception, not a taxonomy to maintain.
- The hub's registry-map (in its MEMORY.md) lists the active chantiers across repos — that's the cross-repo liant. On opening a workspace, filter the registry to its folders, ask the entry point, load per-repo memory on demand via the `[[links]]` DAG.

**Why:** fixed domains pre-suppose frontiers the real work ignores. The chantier is the true unit of work AND the true unit of active memory — so workspace composition and memory loading share the same boundary.

**How to apply:** don't propose a rigid workspace-per-domain scheme. When the user starts a task, the workspace is whatever repos that task needs + `claude-hub`. Keep `.code-workspace` files lean — strip folders unrelated to the current chantier(s). See [[diagram-preview-file]] for the in-workspace preview convention that rides on this.

Note: `fleet` is mis-named — created for kpt (abandoned), now de-facto a floxhub (tracks flox environments; rke2lab `[include]`s `../fleet/flox/{git,k8s,keyhole,pulumi}`). Candidate to rename someday; out of scope for workspace composition.
