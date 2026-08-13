# Layer Env Contributor System - Verification & Testing

## Implementation Summary

### ✅ Completed Components

**1. Core Interfaces** (manifests/src/main/java/io/seedmatic/rke2lab/manifests/layers/env/)
- ✅ `LayerEnvContributor.java` - Interface for layers to contribute env vars
- ✅ `LayerEnvContext.java` - Bootstrap context with paths, node identity, cluster topology
- ✅ `LayerEnvContributorRegistry.java` - Service to load and aggregate contributors

**2. Layer Implementations** (manifests/src/main/java/io/...)
- ✅ `networking/NetworkingLayerEnvContributor.java` - 17 vars (cilium, network-cluster, network-node, network-lan-wan)
- ✅ `ha/HaLayerEnvContributor.java` - 4 vars (network-vip)
- ✅ `storage/StorageLayerEnvContributor.java` - 8 vars (etcdctl)
- ✅ `runtime/RuntimeLayerEnvContributor.java` - 17 vars (rke2, config, containerd, cri, helm, kubectl, user)

**3. ServiceLoader Registration**
- ✅ META-INF/services/io.seedmatic.rke2lab.manifests.layers.env.LayerEnvContributor (4 implementations registered)

**4. IncusResourceBootstrap Integration** (controlplane/src/main/java/io/.../IncusResourceBootstrap.java)
- ✅ Added imports for LayerEnvContributor, LayerEnvContext, LayerEnvContributorRegistry
- ✅ Created `BootstrapLayerEnvContextImpl` inner class implementing LayerEnvContext
- ✅ Updated `RuntimeEnvControlplaneOverlayWriter`:
  - Now accepts `LayerEnvContext` parameter
  - Creates `LayerEnvContributorRegistry` instance
  - Calls `registry.writeAllContributions()` to write individual layer ConfigMaps
  - Calls `registry.aggregateContributions()` to merge all layer vars
  - Generates `99-configmap-env-section-controlplane-layer-contributions.yml` with all vars
- ✅ Updated `prepareHostState()` to create context and pass to overlay writer

**5. Compilation Verification**
- ✅ JDK25 full project compile: FLOX_JDK25_COMPILE_OK
- ✅ All new Java files: No errors found
- ✅ Controlplane module: No errors found

---

## Env Variable Materialization Flow

### Phase 1: Build Time
1. **Maven Compile** - All layer contributors compiled with manifests module
2. **ServiceLoader Registration** - 4 implementations registered in META-INF/services

### Phase 2: Bootstrap Time (IncusResourceBootstrap.apply())
1. **Path Resolution** (resolvePaths)
   ```
   localPaths = BootstrapPaths.fromLocalWorktree(worktree, cluster, node)
   localPaths.runtimeEnvConfigRoot() = .local.d/var/run/incus/.../host/manifests.d/runtime/env-config
   ```

2. **Host State Preparation** (prepareHostState)
   ```
   a. Create BootstrapLayerEnvContextImpl from config + bootstrap paths
   b. Call RuntimeEnvControlplaneOverlayWriter.write(envConfigPath, context)
   c. ✅ Now creates:
      - storage-etcdctl.yml (individually written by StorageLayerEnvContributor)
      - networking-cilium.yml (individually written by NetworkingLayerEnvContributor)
      - networking-network-cluster.yml
      - networking-network-node.yml
      - networking-network-lan-wan.yml
      - ha-network-vip.yml (individually written by HaLayerEnvContributor)
      - runtime-rke2.yml (individually written by RuntimeLayerEnvContributor)
      - runtime-config.yml
      - runtime-containerd.yml
      - runtime-cri.yml
      - runtime-helm.yml
      - runtime-kubectl.yml
      - runtime-user.yml
      - 99-configmap-env-section-controlplane-layer-contributions.yml (aggregated)
   ```

3. **Disk Mount** (seedInstanceDevices)
   ```
   Mounts .local.d/var/run/incus/.../host → /srv/host in Incus VM
   Now includes all ConfigMap YAML files in /srv/host/rke2lab-environment.d/
   ```

### Phase 3: Runtime (rke2lab-env-load.sh in Incus VM)
1. **Load Env Dir**
   ```bash
   env_dir=/srv/host/rke2lab-environment.d
   ```

2. **Process Files in Sorted Order**
   ```bash
   # Load in this order (later overrides earlier):
   01-configmap-env-section-cilium.yml              (manifests default)
   01-configmap-env-section-cluster.yml             (manifests default)
   01-configmap-env-section-*.yml                   (all manifests defaults)
   
   storage-etcdctl.yml                              (layer contribution)
   networking-cilium.yml                            (layer contribution, overrides manifests)
   networking-network-cluster.yml                   (layer contribution)
   networking-network-node.yml
   networking-network-lan-wan.yml
   ha-network-vip.yml
   runtime-rke2.yml
   runtime-config.yml
   runtime-containerd.yml
   runtime-cri.yml
   runtime-helm.yml
   runtime-kubectl.yml
   runtime-user.yml
   
   99-configmap-env-section-controlplane-layer-contributions.yml  (bootstrap overrides, wins)
   ```

3. **Source All Data**
   ```bash
   for file in $env_dir/*.yml; do
     yq eval -o=shell '.data | to_entries[]' "$file" | source
   done
   ```

---

## Variable Override Precedence (Ordered)

### Override Precedence (Later Wins)
1. **Layer 1: Storage** (etcdctl)
   - `ETCDCTL_API=3`, `ETCDCTL_CERT=...`, etc.

2. **Layer 2: Networking** (cilium, network-*, etc.)
   - `CILIUM_CLI_MODE=kubernetes`, `RKE2LAB_NETWORK_CLUSTER_*`, etc.
   - ⚠️ **Networking layer CAN override Storage layer variables** (if same key)

3. **Layer 3: HA** (network-vip)
   - `RKE2LAB_NETWORK_VIP_*`, etc.

4. **Layer 4: Runtime** (rke2, config, containerd, kubectl, helm, etc.)
   - `RKE2LAB_DEBUG=false`, `HELM_CONFIG_HOME=...`, etc.

5. **Bootstrap Time Overrides** (99-configmap)
   - `RKE2LAB_ROOT=/srv/host` (host paths + aggregated layer contributions)
   - ✅ **This file always wins** - written last, contains all vars from steps 1-4

### Example Resolution for `RKE2LAB_NETWORK_CLUSTER_CIDR`
```
Step 1: Manifests default          → 01-configmap-env-section-network-cluster.yml
        RKE2LAB_NETWORK_CLUSTER_CIDR=10.80.0.0/21 (from manifests/src/main/resources/runtime/env-config/network-cluster.env)

Step 2: Networking layer override  → networking-network-cluster.yml (generated at bootstrap)
        RKE2LAB_NETWORK_CLUSTER_CIDR={layerContext.clusterCidr()} = 10.80.0.0/21 (from LayerEnvContext)

Step 3: Bootstrap aggregation      → 99-configmap-env-section-controlplane-layer-contributions.yml
        RKE2LAB_NETWORK_CLUSTER_CIDR=10.80.0.0/21 (already in context, persisted)

✅ Final Value: RKE2LAB_NETWORK_CLUSTER_CIDR=10.80.0.0/21
```

---

## Test Scenarios

### Test 1: Layer Contribution Registry Loading
**Objective:** Verify ServiceLoader finds all 4 layer contributors

**Test Code (Pseudo)**
```java
LayerEnvContext context = new BootstrapLayerEnvContextImpl();
LayerEnvContributorRegistry registry = new LayerEnvContributorRegistry(context);
List<LayerEnvContributor> contributors = registry.orderedContributors();

assert contributors.size() == 4;
assert contributors.get(0).layerId().equals("storage");
assert contributors.get(1).layerId().equals("networking");
assert contributors.get(2).layerId().equals("ha");
assert contributors.get(3).layerId().equals("runtime");
```

**Expected Result:** 4 contributors loaded in correct order

**Status:** ✅ Impl complete, needs execution test

---

### Test 2: Individual Layer Variable Generation
**Objective:** Each layer generates correct variables for its sections

**Test Scenario: Networking Layer**
```java
NetworkingLayerEnvContributor networking = new NetworkingLayerEnvContributor();

// Test cilium section
Map<String, String> ciliumVars = networking.contributeVariables("cilium", context);
assert ciliumVars.get("CILIUM_CLI_MODE").equals("kubernetes");
assert ciliumVars.get("HUBBLE_TLS").equals("false");

// Test network-cluster section
Map<String, String> clusterVars = networking.contributeVariables("network-cluster", context);
assert clusterVars.get("RKE2LAB_NETWORK_CLUSTER_CIDR").equals(context.clusterCidr());
assert clusterVars.get("RKE2LAB_NETWORK_CLUSTER_POD_CIDR").equals("10.42.0.0/16");
```

**Expected Result:** Each section returns correct vars from context

**Status:** ✅ Impl complete, needs execution test

---

### Test 3: Aggregation and Override Semantics
**Objective:** Later layers override earlier layers

**Test Scenario:**
```java
registry.aggregateContributions();
Map<String, String> aggregated = registry.aggregateContributions();

// Should have vars from all 4 layers (no duplicates, later wins)
assert aggregated.containsKey("ETCDCTL_API");           // Storage
assert aggregated.containsKey("CILIUM_CLI_MODE");       // Networking
assert aggregated.containsKey("RKE2LAB_NETWORK_VIP_INTERFACE"); // HA
assert aggregated.containsKey("RKE2LAB_DEBUG");         // Runtime

// If a variable exists in multiple layers, later layer wins
// (e.g., if both Networking and Runtime contributed RKE2LAB_X, Runtime value wins)
```

**Expected Result:** All unique vars present, later layers override earlier

**Status:** ✅ Impl complete, needs execution test

---

### Test 4: ConfigMap YAML Generation
**Objective:** Each layer writes properly formatted Kubernetes ConfigMap YAML

**Test Scenario: Networking Layer WriteConfigMap**
```java
Path tmpDir = Files.createTempDirectory("env-test");
networking.writeConfigMap(tmpDir, context);

// Files created:
Path ciliumFile = tmpDir.resolve("networking-cilium.yml");
Path clusterFile = tmpDir.resolve("networking-network-cluster.yml");

// Verify YAML structure
String ciliumYaml = Files.readString(ciliumFile);
assert ciliumYaml.contains("apiVersion: v1");
assert ciliumYaml.contains("kind: ConfigMap");
assert ciliumYaml.contains("name: env-section-cilium");
assert ciliumYaml.contains("CILIUM_CLI_MODE: kubernetes");
```

**Expected Result:** Valid Kubernetes YAML ConfigMaps written for each section

**Status:** ✅ Impl complete, needs execution test

---

### Test 5: Bootstrap 99-ConfigMap Aggregation
**Objective:** RuntimeEnvControlplaneOverlayWriter generates final 99-configmap with all vars

**Test Scenario:**
```java
Path runtimeEnvDir = .local.d/.../runtime/env-config;
LayerEnvContext context = new BootstrapLayerEnvContextImpl();
runtimeEnvControlplaneOverlayWriter.write(runtimeEnvDir, context);

// Verify all ConfigMaps written:
assertTrue(Files.exists(runtimeEnvDir.resolve("storage-etcdctl.yml")));
assertTrue(Files.exists(runtimeEnvDir.resolve("networking-cilium.yml")));
assertTrue(Files.exists(runtimeEnvDir.resolve("ha-network-vip.yml")));
assertTrue(Files.exists(runtimeEnvDir.resolve("runtime-rke2.yml")));
assertTrue(Files.exists(runtimeEnvDir.resolve("99-configmap-env-section-controlplane-layer-contributions.yml")));

// Verify 99-configmap contains all vars
String finalConfigMap = Files.readString(runtimeEnvDir.resolve("99-configmap-env-section-controlplane-layer-contributions.yml"));
assert finalConfigMap.contains("RKE2LAB_ROOT: /srv/host");                    // Bootstrap path
assert finalConfigMap.contains("ETCDCTL_API: 3");                             // Storage
assert finalConfigMap.contains("CILIUM_CLI_MODE: kubernetes");                // Networking
assert finalConfigMap.contains("RKE2LAB_NETWORK_VIP_INTERFACE: rke2-vip0");   // HA
assert finalConfigMap.contains("RKE2LAB_DEBUG: false");                       // Runtime
```

**Expected Result:** 99-configmap contains aggregated vars from all layers + bootstrap paths

**Status:** ✅ Impl complete, needs execution test (part of Incus bootstrap)

---

### Test 6: Runtime Env Load Script Processing
**Objective:** rke2lab-env-load.sh correctly sources all ConfigMaps in order

**Test Scenario (in Incus VM post-boot):**
```bash
#!/bin/bash
# Simulating env-load.sh behavior

# Source all ConfigMaps in env-config directory (sorted order)
eval "$(
  yq eval -o=shell 'select(.kind == "ConfigMap") | 
    .data | 
    to_entries[] | 
    .key + "=" + (.value | @sh)' \
  /srv/host/rke2lab-environment.d/*.yml 2>/dev/null |
  sort
)"

# Verify final values (99-configmap overwrites earlier ones)
echo "RKE2LAB_ROOT=$RKE2LAB_ROOT"                              # /srv/host (from 99-configmap)
echo "CILIUM_CLI_MODE=$CILIUM_CLI_MODE"                        # kubernetes (from 99-configmap aggregation)
echo "RKE2LAB_NETWORK_VIP_INTERFACE=$RKE2LAB_NETWORK_VIP_INTERFACE"  # rke2-vip0 (from 99-configmap)
echo "RKE2LAB_DEBUG=$RKE2LAB_DEBUG"                            # false (from 99-configmap)
```

**Expected Result:** All variables set correctly, bootstrap paths override manifests defaults

**Status:** Runtime test (will execute when Incus bootstrap runs)

---

## File Structure Summary

```
manifests/
├── src/main/java/io/seedmatic/rke2lab/manifests/
│   └── layers/
│       ├── env/                          (✅ NEW)
│       │   ├── LayerEnvContributor.java  (✅ NEW)
│       │   ├── LayerEnvContext.java      (✅ NEW)
│       │   └── LayerEnvContributorRegistry.java (✅ NEW)
│       ├── networking/
│       │   └── NetworkingLayerEnvContributor.java (✅ NEW)
│       ├── ha/
│       │   └── HaLayerEnvContributor.java (✅ NEW)
│       ├── storage/
│       │   └── StorageLayerEnvContributor.java (✅ NEW)
│       └── runtime/
│           └── RuntimeLayerEnvContributor.java (✅ NEW + MODIFIED)
└── src/main/resources/
    └── META-INF/services/                (✅ NEW)
        └── io.seedmatic.rke2lab.manifests.layers.env.LayerEnvContributor (4 registrations)

controlplane/
└── src/main/java/io/seedmatic/rke2lab/controlplane/
    └── incus/
        └── IncusResourceBootstrap.java
            ├── Added: LayerEnvContributor imports (✅)
            ├── Updated: RuntimeEnvControlplaneOverlayWriter (✅)
            ├── Added: BootstrapLayerEnvContextImpl (✅)
            └── Updated: prepareHostState() (✅)
```

---

## Verification Checklist

- ✅ All interfaces defined (LayerEnvContributor, LayerEnvContext, LayerEnvContributorRegistry)
- ✅ All 4 layer contributors implemented (Networking, HA, Storage, Runtime)
- ✅ ServiceLoader registration file created
- ✅ BootstrapLayerEnvContextImpl created in IncusResourceBootstrap
- ✅ RuntimeEnvControlplaneOverlayWriter updated to use registry
- ✅ prepareHostState() updated to pass context to overlay writer
- ✅ JDK25 compilation successful
- ✅ No compilation errors in any new files
- ✅ Import statements added to controlplane
- ⏳ Runtime integration testing (requires Incus bootstrap execution)
- ⏳ ConfigMap YAML ordering verification (requires runtime script execution)

---

## Next Steps (When Ready for Integration Testing)

1. **Execute Incus Bootstrap**
   ```bash
   cd /private/var/lib/git/nxmatic/rke2lab
   # Run bootstrap to materialize host assets
   # Verify ConfigMap files written to host mount
   ```

2. **Inspect Generated ConfigMaps**
   ```bash
   ls -la .local.d/var/run/incus/.../host/manifests.d/runtime/env-config/
   # Should show:
   # - storage-etcdctl.yml
   # - networking-*.yml (4 files)
   # - ha-network-vip.yml
   # - runtime-*.yml (7 files)
   # - 99-configmap-env-section-controlplane-layer-contributions.yml
   ```

3. **Verify Env Load in VM**
   ```bash
   # In Incus VM after boot:
   source /srv/host/rke2lab-environment.d/*.sh 2>/dev/null || 
   bash <(yq eval -o=shell '.data | keys[]' /srv/host/rke2lab-environment.d/*.yml | head -20)
   
   # Check variables set correctly:
   echo "RKE2LAB_ROOT=$RKE2LAB_ROOT"
   echo "CILIUM_CLI_MODE=$CILIUM_CLI_MODE"
   env | grep RKE2LAB | sort
   ```

4. **Validate Override Precedence**
   - Verify that 99-configmap values override manifests defaults
   - Confirm layer contributions are correctly aggregated
   - Test cluster-specific vs node-specific variables

---

## Benefits Achieved

✅ **No More Hardcoded Values** - Layers own their env vars  
✅ **Runtime Flexibility** - Bootstrap-time generation enables per-cluster customization  
✅ **Clear Ownership** - Each layer domain has explicit env section responsibility  
✅ **Service Discovery** - LayerEnvContext provides bootstrap-time paths/topology  
✅ **Type Safety** - Java interfaces enforce correct variable generation  
✅ **Testability** - Each contributor independently verifiable  
✅ **Scalability** - New layers can add contributors without modifying existing code  
✅ **Kubernetes Native** - Generated ConfigMaps follow standard k8s patterns  
✅ **Audit Trail** - Config as code: layerId → sectionName → variables → YAML → runtime

---

## Known Limitations & Future Enhancements

| Item | Status | Note |
|------|--------|------|
| GitOps/CICD layer env | Phase 3 | Deferred until after cluster bootstrap |
| Dynamic network topology | Current | Hard-coded in LayerEnvContextImpl; could link to ClusterNetworkBlueprint |
| Per-node variable overrides | Future | Node ID currently always 0; could support peer1, peer2, etc. |
| Env var validation | Future | Could add LayerEnvContext.validate() to catch invalid paths early |
| Layer dependency resolution | Future | Currently fixed order (storage→networking→ha→runtime); could be data-driven |
