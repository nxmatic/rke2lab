# Layer Environment Variable Contribution Architecture

## Problem Statement

Currently, all 17 env variable sections are hardcoded in `manifests/src/main/resources/runtime/env-config/*.env` files at build time. This makes it impossible for different layer domains to own and manage their own environment variables at runtime.

**Goal:** Enable each layer domain to contribute environment variables through a registry interface, materializing them as ConfigMaps during controlplane bootstrap.

---

## Design Principles

1. **Layer Autonomy**: Each layer owns its environment variables; no cross-layer env dependencies
2. **Single Responsibility**: Controlplane orchestrates layer contributions, doesn't know layer internals
3. **Ordering Semantics**: Clear precedence rules (bootstrap paths > layer contributions > manifests defaults)
4. **Immutability**: Build-time defaults remain in manifests as fallback; runtime overrides are generated
5. **Testability**: Each layer contributor is independently verifiable

---

## Interface Definition

### 1. `LayerEnvContributor` Interface

```java
package io.seedmatic.rke2lab.manifests.layers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Contract for layer domains to contribute environment variables.
 * Implementations are registered via ServiceLoader and aggregated by IncusResourceBootstrap.
 */
public interface LayerEnvContributor {

    /**
     * Unique identifier for this contributor (e.g., "networking", "storage", "ha").
     * Used for ConfigMap naming and override ordering.
     */
    String layerId();

    /**
     * List of environment sections this layer contributes.
     * Examples: ["cilium", "network-cluster", "network-node"]
     */
    List<String> contributedSections();

    /**
     * Generate environment variables for the given section.
     * 
     * @param sectionName the section being contributed (one of contributedSections())
     * @param context read-only context with bootstrap paths, node identity, cluster topology
     * @return map of KEY=VALUE environment variables
     * @throws IOException if contribution fails
     */
    Map<String, String> contributeVariables(String sectionName, LayerEnvContext context)
        throws IOException;

    /**
     * Optional: Write ConfigMap YAML for this contribution to disk.
     * Default implementation uses Kubernetes API conventions.
     * 
     * @param outputDir directory where ConfigMap YAML will be written
     * @param context bootstrap context
     * @throws IOException if write fails
     */
    default void writeConfigMap(Path outputDir, LayerEnvContext context) throws IOException {
        for (String section : contributedSections()) {
            String configMapName = "env-section-" + section;
            Map<String, String> variables = contributeVariables(section, context);
            
            // Write standard Kubernetes ConfigMap YAML
            String yaml = generateConfigMapYaml(configMapName, section, variables);
            Path outputFile = outputDir.resolve(layerId() + "-" + section + ".yml");
            java.nio.file.Files.writeString(outputFile, yaml);
        }
    }

    /**
     * Standard ConfigMap YAML generation (reusable by all contributors).
     */
    static String generateConfigMapYaml(
        String name,
        String section,
        Map<String, String> variables) {
        
        StringBuilder yaml = new StringBuilder();
        yaml.append("---\n");
        yaml.append("apiVersion: v1\n");
        yaml.append("kind: ConfigMap\n");
        yaml.append("metadata:\n");
        yaml.append("  annotations:\n");
        yaml.append("    config.kubernetes.io/local-config: \"true\"\n");
        yaml.append("    env.rke2lab.nxmatic.io/section: ").append(section).append("\n");
        yaml.append("    rke2lab.nxmatic.io/managed-by: layer-contributor\n");
        yaml.append("  name: ").append(name).append("\n");
        yaml.append("data:\n");
        
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            yaml.append("  ").append(entry.getKey()).append(": ")
                .append(quoteIfNeeded(entry.getValue())).append("\n");
        }
        
        return yaml.toString();
    }

    /**
     * Quote YAML values if they contain spaces or special chars.
     */
    static String quoteIfNeeded(String value) {
        if (value.isEmpty() || value.contains(" ") || value.contains(":") || value.equals("false") || value.equals("true")) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return value;
    }
}
```

### 2. `LayerEnvContext` Interface

```java
package io.seedmatic.rke2lab.manifests.layers;

import java.nio.file.Path;

/**
 * Read-only bootstrap context passed to layer env contributors.
 * Provides access to bootstrap-time paths, node identity, and cluster topology.
 */
public interface LayerEnvContext {

    // Bootstrap Paths
    Path rootPath();                      // /srv/host
    Path envDirPath();                    // /srv/host/rke2lab-environment.d
    Path scriptsDirPath();                // /srv/host/systemd-scripts.d
    Path systemdDirPath();                // /srv/host/systemd-units.d
    Path configDirPath();                 // /srv/host/rke2-config.d
    Path cloudconfigNocloudDirPath();     // /srv/host/cloudconfig-nocloud.d
    Path manifestsDirPath();              // /srv/host/rke2-manifests.d
    Path sharedDirPath();                 // /srv/host/rke2lab-share.d
    Path kubeconfigDirPath();             // /srv/host/rke2lab-kube.d

    // Node Identity
    int nodeId();                         // 0 for master
    String nodeName();                    // "master"
    String nodeKind();                    // "server" for control plane

    // Cluster Identity
    int clusterId();                      // 0
    String clusterName();                 // "bioskop"
    String clusterToken();                // "bioskop"
    String clusterDomain();               // "cluster.local"

    // Network Topology (populated by networking layer during init)
    String clusterCidr();                 // "10.80.0.0/21"
    String clusterPodCidr();              // "10.42.0.0/16"
    String clusterServiceCidr();          // "10.43.0.0/16"
    String nodeHostInetAddr();            // "10.80.0.10"
}
```

### 3. `LayerEnvContributorRegistry` Service

```java
package io.seedmatic.rke2lab.manifests.layers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Aggregates all LayerEnvContributor implementations and manages env var generation.
 * Used by IncusResourceBootstrap to orchestrate layer contributions.
 */
public class LayerEnvContributorRegistry {

    private final List<LayerEnvContributor> contributors;
    private final LayerEnvContext context;

    public LayerEnvContributorRegistry(LayerEnvContext context) {
        this.context = context;
        this.contributors = loadContributors();
    }

    /**
     * Load all registered LayerEnvContributor implementations via ServiceLoader.
     */
    private List<LayerEnvContributor> loadContributors() {
        var loader = ServiceLoader.load(LayerEnvContributor.class);
        var list = new ArrayList<LayerEnvContributor>();
        for (var contributor : loader) {
            list.add(contributor);
        }
        return list;
    }

    /**
     * Order contributors by layer priority (deterministic).
     * Execution order: storage → networking → ha → runtime → gitops.
     */
    public List<LayerEnvContributor> orderedContributors() {
        var order = Map.of(
            "storage", 1,
            "networking", 2,
            "ha", 3,
            "runtime", 4,
            "gitops", 5
        );
        contributors.sort((a, b) ->
            order.getOrDefault(a.layerId(), 99)
                .compareTo(order.getOrDefault(b.layerId(), 99))
        );
        return contributors;
    }

    /**
     * Aggregate all layer contributions into a merged env map (for 99-configmap generation).
     * Later layers override earlier ones (storage < networking < ha < runtime < gitops).
     */
    public Map<String, String> aggregateContributions() throws IOException {
        var aggregated = new HashMap<String, String>();
        for (var contributor : orderedContributors()) {
            for (String section : contributor.contributedSections()) {
                var vars = contributor.contributeVariables(section, context);
                aggregated.putAll(vars);  // Later layers override
            }
        }
        return aggregated;
    }

    /**
     * Write all layer contributions as individual ConfigMap YAML files.
     */
    public void writeAllContributions(Path outputDir) throws IOException {
        for (var contributor : orderedContributors()) {
            contributor.writeConfigMap(outputDir, context);
        }
    }
}
```

---

## Implementation Flow

### Controlplane Bootstrap Sequence

```
1. IncusResourceBootstrap.execute()
   ↓
2. seedInstanceDevices()           — mount /srv/host with all subfolder .d paths
   ↓
3. prepareHostState()
   ↓
4. New: createLayerEnvContext()    — build LayerEnvContext from bootstrap params
   ↓
5. New: LayerEnvContributorRegistry(context)  — load all contributors via ServiceLoader
   ↓
6. New: registry.orderedContributors()        — sort by: storage, networking, ha, runtime, gitops
   ↓
7. For each contributor:
   a. contributor.contributeVariables(section, context)  — generate map
   b. contributor.writeConfigMap(outputDir, context)     — write YAML
   ↓
8. registry.aggregateContributions()          — merge all into single override map
   ↓
9. New: RuntimeEnvControlplaneOverlayWriter writes 99-configmap-env-section-layer-contributions.yml
   (with aggregated vars from all layers + existing 11 HOST_*_PATH constants)
   ↓
10. Host bootstrap complete, all env ConfigMaps in place
```

### Runtime Env Load Sequence

When `rke2lab-env-load.sh` runs post-boot:

```
1. source <bootstrap-provided-env>     — source any env vars from kernel command line / cloud-init
   ↓
2. env_dir=/srv/host/rke2lab-environment.d
   ↓
3. Load all *.yml files in sorted order:
   a. 01-configmap-env-section-*.yml   (original manifests defaults)
   b. 02-configmap-env-section-*.yml   (layer contributions in order)
   c. 99-configmap-env-section-layer-contributions.yml  (controlplane overrides)
   ↓
4. For each file: yq eval -o=shell '.data | to_entries[]' | source
   ↓
5. Later files override earlier ones (99-config-* wins)
```

---

## Implementation Roadmap

### Phase 2A: `NetworkingLayerEnvContributor`
**Location:** `manifests/src/main/java/io/seedmatic/rke2lab/manifests/layers/networking/NetworkingLayerEnvContributor.java`

```java
public class NetworkingLayerEnvContributor implements LayerEnvContributor {
    @Override
    public String layerId() { return "networking"; }

    @Override
    public List<String> contributedSections() {
        return List.of("cilium", "network-cluster", "network-node", "network-lan-wan");
    }

    @Override
    public Map<String, String> contributeVariables(String sectionName, LayerEnvContext context) {
        return switch (sectionName) {
            case "cilium" -> Map.of(
                "CILIUM_CLI_MODE", "kubernetes",
                "CILIUM_CLI_CONTEXT", "default",
                "HUBBLE_SERVER", "localhost:4245",
                "HUBBLE_TLS", "false"
            );
            case "network-cluster" -> Map.of(
                "RKE2LAB_NETWORK_CLUSTER_CIDR", context.clusterCidr(),
                "RKE2LAB_NETWORK_CLUSTER_POD_CIDR", context.clusterPodCidr(),
                "RKE2LAB_NETWORK_CLUSTER_SERVICE_CIDR", context.clusterServiceCidr()
                // ... more
            );
            // ... other sections
            default -> Map.of();
        };
    }
}
```

Register via `META-INF/services/io.seedmatic.rke2lab.manifests.layers.LayerEnvContributor`:
```
io.seedmatic.rke2lab.manifests.layers.networking.NetworkingLayerEnvContributor
```

### Phase 2B: `HaLayerEnvContributor`
**Location:** `manifests/src/main/java/io/seedmatic/rke2lab/manifests/layers/ha/HaLayerEnvContributor.java`

Contributes: `network-vip.env`

### Phase 2C: `StorageLayerEnvContributor`
**Location:** `manifests/src/main/java/io/seedmatic/rke2lab/manifests/layers/storage/StorageLayerEnvContributor.java`

Contributes: `etcdctl.env`

### Phase 2D: `RuntimeLayerEnvContributor`
**Location:** `manifests/src/main/java/io/seedmatic/rke2lab/manifests/layers/runtime/RuntimeLayerEnvContributor.java`

Contributes: `rke2.env`, `config.env`, `containerd.env`, `cri.env`, `helm.env`, `kubectl.env`, `user.env`

### Phase 2E: Update `IncusResourceBootstrap`
- Add `createLayerEnvContext()` method to build context from bootstrap params
- Add `writeLayerContributions()` method that instantiates `LayerEnvContributorRegistry` and writes all ConfigMaps
- Update `99-configmap-env-section-layer-contributions.yml` to aggregate all layer contributions
- Call `writeLayerContributions()` from `prepareHostState()` before cleanup

---

## Benefits

| Benefit | How Achieved |
|---------|------------|
| **Layer Autonomy** | Each layer owns its env vars via `LayerEnvContributor` implementation |
| **No Rebuilds** | Layer env contributions generated at controlplane bootstrap time (99-configmap-*) |
| **Clear Ownership** | Inventory document explicitly assigns sections to layers |
| **Testability** | Each contributor can be tested in isolation |
| **Override Semantics** | File naming (01-*, 99-*) enforces clear precedence |
| **Centralized Paths** | 11 `HOST_*_PATH` constants in IncusResourceBootstrap + runtime context |
| **Future-Proof** | New layers can contribute env vars without modifying existing code (ServiceLoader) |

---

## Configuration as Code

Once implemented, the env variable ownership system becomes a clear, auditable contract:

```
manifests/src/main/resources/runtime/env-config/*.env
  ↓ (build-time defaults, fallback only)
  
layer-contrib-*.yml (generated at controlplane bootstrap)
  ↓ (layer domain ownership, service discovery config)
  
99-configmap-env-section-layer-contributions.yml (generated at bootstrap)
  ↓ (bootstrap-time overrides from all layers, ordered)
  
rke2lab-env-load.sh (process at runtime)
  ↓ (loads all YAML in order, later overrides earlier)
  
Operational Environment (sourced by scripts)
```

This creates an immutable audit trail: layerId → sectionName → variables → ConfigMap YAML → runtime activation.
