---
name: flake-mutual-dependency-follows-root
description: "Break a mutual/cyclic flake dependency with reciprocal `inputs.<other>.inputs.<self>.follows = \"\"` (empty follows = the ROOT flake, a fixpoint); works unless there is a true VALUE cycle"
metadata:
  type: reference
---

Two flakes that each need an output of the other (A inputs B to read B's output; B inputs A to
read A's output) look like a forbidden flake-input cycle. **Resolved natively** by each side
overriding the other's back-reference *to itself* with an **empty follows**:

```nix
# flake A
inputs.b.url = "github:org/b";
inputs.b.inputs.a.follows = "";   # B's `a` input follows the ROOT (= A)
# flake B — reciprocal, so B also builds standalone
inputs.a.url = "github:org/a";
inputs.a.inputs.b.follows = "";
```

**`follows = ""` = follow the ROOT flake** (empty lock path `[]`), a fixpoint — it does NOT
nullify/remove the input. The current top-level flake re-injects *itself* into its dependency's
back-reference, so **both flakes see each other's REAL, complete outputs**; when a contributor is
the root, the merged data it reads from the owner INCLUDES its own contribution.

Empirically validated 2026-08-17 (real `nix`, two repro flakes, `path:` inputs):
- both directions (consume + contribute) resolve to real cross-data, not empty;
- `flake.lock` **writes and terminates** — pure/reproducible; **no committed snapshot, git subtree,
  or `builtins.getFlake` needed**;
- the lock renders the cut input as `"inputs": { "<self>": [] }` (empty path = root);
- **only hard limit: a genuine VALUE cycle** (A.value ← B.value ← A.value) → `infinite recursion`;
  no mechanism resolves that.

**Invariant to stay acyclic:** the piece the owner pulls from the contributor must not itself read
back the owner's merged output — keep the contributed piece **self-contained**.

**Both sides must declare the reciprocal cut** (each is independently a root when built). Validated
with `path:` inputs; `follows` is graph-level (independent of fetch type), so `github:` refs should
behave identically — re-validate once real refs are wired.

Concrete use — the **seedmatic catalog federation**: ndh owns the merged
`catalog.netplan.{segments,asns}`; nnh (flowlab) and rke2lab each expose a self-contained
`lib.networkBlueprint` that ndh unions AND consume the catalog. This pattern lets each keep the
`ndh` flake input for live consumption while ndh inputs them for the blueprint — no cycle, no
snapshot. Supersedes the earlier snapshot/subtree/composition-root workarounds considered for the
same problem. See [[flowlab:asn-network-labeling]]. Repro flakes were at `/private/tmp/cyc`.
