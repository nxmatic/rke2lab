package io.nxmatic.rk2lab.controlplane.policy;

import java.util.Map;

/**
 * Debug policy for controlplane-managed runtime features. Aligned by manifest domain (mesh,
 * networking) so each toggle gates the same shape of behavior — flipping a domain on swaps every
 * pod inside it to its {@code <domain>/<workload>-debug} flox env and adds a shell sidecar with
 * delve + SYS_PTRACE for live attach. The NRI plugin daemon has its own toggle since it's the
 * carrier (debug build of the plugin itself, not a workload).
 */
public record DebugPolicy(
    boolean meshEnabled, boolean networkingEnabled, boolean floxNriPluginEnabled) {

  public Map<String, String> toEnvMap() {
    return Map.of(
        "RKE2LAB_POLICY_DEBUG_MESH_ENABLED",
        Boolean.toString(meshEnabled),
        "RKE2LAB_POLICY_DEBUG_NETWORKING_ENABLED",
        Boolean.toString(networkingEnabled),
        "RKE2LAB_POLICY_DEBUG_NRI_PLUGINS_FLOX_ENABLED",
        Boolean.toString(floxNriPluginEnabled));
  }

  public Map<String, Object> toOutputMap() {
    return Map.of(
        "policyDebugMeshEnabled",
        meshEnabled,
        "policyDebugNetworkingEnabled",
        networkingEnabled,
        "policyDebugFloxNriPluginEnabled",
        floxNriPluginEnabled);
  }
}
