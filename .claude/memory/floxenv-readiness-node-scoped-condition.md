---
name: floxenv-readiness-node-scoped-condition
description: "Design (converged, NOT coded) — flox-wait must gate on FloxEnv realized ON ITS OWN node at the current generation, via status.realized[], not the incoherent aggregate conditions[Ready]"
metadata:
  node_type: memory
  type: project
---

**The right readiness condition for flox-wait = "the FloxEnv is realised on the SAME node the pod runs on, at the current spec generation" (design converged 2026-09-06, NOT coded).**

**Context.** flox-wait is the gate that blocks a flox-injected pod's container until its env is present, so the NRI plugin's bind-mount of the node's GC-root succeeds. Today it watches the **GC-root FILE** on the node (`/nix/var/nix/gcroots/flox-runtime/env/<folder>/<name>`). Question raised: why not rely on the CONTROLLER's FloxEnv `.status` instead? (User: "je préfère qu'on repose sur le contrôleur … il nous manque le/s node/s sur lesquels ont été réalisés les flox envs pour définir la bonne condition.")

**★ THE CONDITION (grave this):**
```
∃ realized[n] :  n.node == $MY_NODE
              && n.ready
              && n.observedGeneration == floxenv.metadata.generation
```
`$MY_NODE` = the pod's node via the downward API (`fieldRef: spec.nodeName`). = "the env, in its CURRENT version, is realised on MY node." The generation check is REQUIRED: `gcrootPath` is deterministic per ref (NOT per generation), so a spec bump OVERWRITES the same path — a stale `ready=true` from a prior generation would otherwise unblock the pod onto the old subtree (a race window during re-realisation).

**Verified at the source (flox-controller `develop`, worktree `/private/var/lib/git/seedmatic/flox-controller.d/main`, ref `develop`):**
- `api/v1alpha1/floxenv_types.go` — `FloxEnvStatus.Realized []NodeRealization` (`+listMapKey=node`), each `{Node, Ready, StorePath, EnvPath, GcrootPath, ObservedGeneration}`. So **per-node realisation ALREADY exists** — the raw data isn't missing.
- `internal/provisioner/exec.go` `Realize()` ordering: `addEnvSubtree` (`nix-store --add`) → `writeGcroot(gcrootPath, subtree)` **[GC-root on disk]** → THEN `return RealizeResult`. The pod shares the node's `/nix` (hostPath/nsenter), so once written it's readlink-able from the pod.
- `internal/controller/floxenv_controller.go` — `res,err := Realize()`; on success `upsertRealization(node, Ready:true, GcrootPath)` THEN `Status().Patch`. So **`realized[node].Ready=true` is posted STRICTLY AFTER the GC-root exists on that node** → a node-scoped waiter that sees it is guaranteed the mount will succeed. User's correctness requirement ("Ready posed only when actually realised on the node") HOLDS — but for `realized[node]`, NOT for the aggregate.

**★ The defect = the AGGREGATE `conditions[Ready]`, NOT the per-node data.** The controller is a per-node agent (`r.NodeName`, DaemonSet-style). It sets the GLOBAL `Ready` condition on the strength of ITS OWN node (`Message: "realized on <node>"`), and `fail()` flips it `False` GLOBALLY. In multi-node it FLAPS/last-writer-wins: node A succeeds→True, node B fails→False overwrites though A is fine. `kubectl wait --for=condition=Ready` + the `Ready` printcolumn read THIS incoherent aggregate. A per-node agent **structurally cannot** own a correct cluster-wide condition (it only knows its node).

**Consumer split (the resolution):**
- **Pod hot path (flox-wait)** wants node-scoped → `realized[$MY_NODE]` (the condition above). No aggregate needed.
- **Cluster-wide "rolled out" consumers** want the aggregate → needs FIXING: either demote it (per-node agent writes ONLY `realized[node]`, never the global condition) OR add a cluster-view AGGREGATOR that computes `Ready` over a DECLARED target node-set (all nodes / a `spec.nodeSelector` / …). Open which; flox-wait doesn't need it.

**RBAC objection DROPPED (user's point):** in-cluster the API is reachable (`kubernetes.default.svc`) + the pod has a projected SA token, so an API-based wait is viable — it just needs a `get/watch FloxEnv` grant on flox-wait's SA (a Role/binding), not an exotic barrier. Caveat: this presumes flox-wait is a k8s-level **container** (has the SA), not the below-kubelet NRI CreateContainer hook.

**Why SWITCH flox-wait from file-watch to the API `realized[]` (the value-add, not just parity):**
1. **★ Depend on the CONTRACT, not the IMPLEMENTATION (user's decisive point — "ce sera plus propre, on reste au niveau de la kube api et on dépend du flox contrôleur et pas de l'implémentation").** The file-watch couples flox-wait to a nix-internal detail — the exact GC-root path convention (`/nix/var/nix/gcroots/flox-runtime/env/<folder>/<name>`) + how the provisioner materialises it. Change the layout / consumption mode / path scheme in the controller and the file-watcher breaks silently. The kube-API `status.realized[]` is the controller's DECLARED contract — flox-wait depends on that stable seam, and the controller stays free to change how it realises underneath. This is the clean frontier: consume the status, not the filesystem.
2. **Failure observability.** The file-watch can only **time out blindly** (a missing GC-root says nothing) — the exact opacity that bit the 2026-09-06 cold-start (purge Job flox-wait timeouts with no cause). The CR carries `Ready=False` + `Reason: RealizeFailed` + the build error → flox-wait can **fail-fast with the controller's cause**.
3. **Generation correctness** (the check above), which a bare path existence-test can't express.

Both the file and `realized[node]` are node-local, controller-written truths; the API one is the properly-decoupled contract.

**Status:** design converged, NOT coded. NEXT (when picked up): locate flox-wait's current file-watch (flox-nri-plugin / the flox-controller pod-injecting webhook), switch it to the node+generation-scoped `realized[]` lookup (downward-API node name), and separately fix the aggregate `conditions[Ready]` (demote or aggregator). See [[flox-env-migration-design]] [[flox-controller-build-deploy-state]] [[tailnet-prune-on-incus-renewal]].
