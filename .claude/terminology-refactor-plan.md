# Terminology Refactor Plan - 2026-06-03

## Context

After completing Phase 1.5 (Component merge + ManifestsUnit rename + ManifestsDomain rename), we identified remaining confusion with "Layer" terminology.

## Final Terminology (Aligned)

| Concept | Old Name | New Name | Location | Role |
|---------|----------|----------|----------|------|
| **Bootstrap temporal phase** | N/A (implicit) | `BootstrapPhase` | seed-master | Stage A → B → C (temporal sequencing) |
| **Manifests functional domain** | LayerDomain ✅ | `ManifestsDomain` ✅ | manifests/ | Groups ManifestsUnits by concern (networking, gitops, cluster-api) |
| **Synthesis unit** | ManifestUnit ✅ | `ManifestsUnit` ✅ | manifests/units/ | Creates K8s manifests + systemd units |
| **Node environment context** | LayerEnvContext | `NodeEnvContext` | manifests/layers/env/ → manifests/node/ | Node identity, paths, cluster config |
| **Environment variable contributor** | LayerEnvContributor | `NodeEnvContributor` | manifests/units/ | Domains contribute env vars TO the node |

## Rationale

**Problem:** "Layer" is overloaded
- Bootstrap phases = temporal (WHEN: stage A → B → C)
- ManifestsDomain = functional (WHAT: networking, gitops)
- LayerEnv = node environment (WHERE: node identity + paths)

**Solution:** Use precise terms
- `BootstrapPhase` = explicit temporal sequencing (seed-master)
- `ManifestsDomain` = explicit functional grouping (manifests)
- `NodeEnv*` = explicit node environment context (manifests/node/)

**Why "Node" not "Bootstrap"?**
- The environment variables belong to the **node** (RKE2LAB_NODE_ID, RKE2LAB_NODE_NAME, paths)
- Domains (cluster, networking, storage, ha) **contribute** variables **to the node**
- "NodeEnvContributor" reads clearly: "I contribute environment variables to the node"

## Next Rename: LayerEnv → NodeEnv

### Files to rename:

```
manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/
├── env/
│   ├── LayerEnvContext.java → NodeEnvContext.java
│   ├── LayerEnvContributor.java → NodeEnvContributor.java
│   ├── LayerEnvContributorRegistry.java → NodeEnvContributorRegistry.java
│   └── DefaultLayerEnvContext.java → DefaultNodeEnvContext.java
└── node/
    └── NodeLayerEnvContributor.java → NodeEnvContributor.java (base impl)

manifests/src/main/java/io/nxmatic/rk2lab/manifests/units/
├── cluster/ClusterLayerEnvContributor.java → ClusterNodeEnvContributor.java
├── networking/NetworkingLayerEnvContributor.java → NetworkingNodeEnvContributor.java
├── storage/StorageLayerEnvContributor.java → StorageNodeEnvContributor.java
├── ha/HighAvailabilityLayerEnvContributor.java → HighAvailabilityNodeEnvContributor.java
└── runtime/env/RuntimeLayerEnvContributor.java → RuntimeNodeEnvContributor.java
```

### Directory rename:

```
manifests/src/main/java/io/nxmatic/rk2lab/manifests/layers/
  → manifests/src/main/java/io/nxmatic/rk2lab/manifests/node/

Keep:
  node/NodeEnvContext.java (interface)
  node/NodeEnvContributor.java (interface)
  node/NodeEnvContributorRegistry.java
  node/DefaultNodeEnvContext.java (impl)
```

### Text replacements:

1. Class names:
   - `LayerEnvContext` → `NodeEnvContext`
   - `LayerEnvContributor` → `NodeEnvContributor`
   - `LayerEnvContributorRegistry` → `NodeEnvContributorRegistry`
   - `DefaultLayerEnvContext` → `DefaultNodeEnvContext`
   - `*LayerEnvContributor` → `*NodeEnvContributor`

2. Method names in contributor interface:
   - `layerId()` → `domainId()` (clearer: it's the domain contributing)
   
3. Package imports:
   - `io.nxmatic.rk2lab.manifests.layers.env` → `io.nxmatic.rk2lab.manifests.node`

4. Javadoc references to "layer" → "domain" or "node"

## Workflow Script Pattern

Use 3-phase workflow:
1. **Rename Files** - git mv all files
2. **Update References** - sed replace class names in all Java files
3. **Rename Methods** - specific method renames (layerId → domainId)

## Completed Work (2026-06-03)

✅ Merge 23 Components → ManifestsUnits (commit 443380fb)
✅ Rename ManifestUnit → ManifestsUnit (plural)
✅ Rename LayerDomain → ManifestsDomain (commit d40a8377)
✅ Documentation: docs/manifests-architecture.adoc with C4 diagrams
✅ Rename LayerEnv → NodeEnv (this plan):
   - Interfaces: LayerEnvContext→NodeEnvContext, LayerEnvContributor→NodeEnvContributor,
     LayerEnvContributorRegistry→NodeEnvContributorRegistry, DefaultLayerEnvContext→DefaultNodeEnvContext
   - Node-identity contributor: NodeLayerEnvContributor → NodeEnvIdentityContributor
     (renamed to avoid collision with the NodeEnvContributor interface)
   - Domain contributors: Cluster/Networking/Storage/HighAvailability/Runtime *LayerEnvContributor → *NodeEnvContributor
   - Moved manifests/layers/env/ + manifests/layers/node/ → manifests/node/ (layers/ dir deleted)
   - Method layerId() → domainId(); seed-master DefaultBootstrapLayerEnvContext → DefaultBootstrapNodeEnvContext,
     orderedLayers → orderedDomains
   - FIXED pre-existing broken SPI: META-INF/services file pointed at stale manifests.layers.* packages;
     rewritten to manifests.units.* / manifests.node and renamed to the NodeEnvContributor service
   - Cleaned stale "layer" wording in javadoc; updated docs (manifest-conditional-inclusion, authored-notes-import)
   - manifests + seed-master compile clean

## Remaining (separate tasks, not part of NodeEnv rename)

1. Introduce BootstrapPhase terminology for temporal bootstrap stages (seed-master)
2. Reimplement DomainRegistrars — DefaultManifestSynthesisService.buildDomainRegistry() throws UnsupportedOperationException
3. Fix CiliumConfigManifestsUnit.apply(ManifestsUnitContext) self-instantiation bug
4. Clean up dead null-scope constructor in AbstractManifestsUnit
5. Update docs/manifests-architecture.adoc with BootstrapPhase terminology

## Notes

- NodeEnvContributor implementations stay in their domain packages (networking/, cluster/, etc.)
- Only the base infrastructure moves to manifests/node/
- This completes the terminology cleanup from Phase 1.5
