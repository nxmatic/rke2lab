package io.nxmatic.rk2lab.controlplane.policy;

import java.util.Map;

/** Debug policy for controlplane-managed runtime features. */
public record DebugPolicy(boolean kdnsEnabled, boolean kdnsSuspend, boolean floxNriPluginEnabled) {

  public String kdnsFloxEnvironment() {
    return "networking/kdns";
  }

  public Map<String, String> toEnvMap() {
    final Map<String, String> env =
        Map.of(
            "RKE2LAB_POLICY_DEBUG_KDNS_ENABLED",
            Boolean.toString(kdnsEnabled),
            "RKE2LAB_POLICY_DEBUG_KDNS_SUSPEND",
            Boolean.toString(kdnsSuspend),
            "RKE2LAB_POLICY_DEBUG_KDNS_FLOX_ENV",
            kdnsFloxEnvironment(),
            "RKE2LAB_POLICY_DEBUG_NRI_PLUGINS_FLOX_ENABLED",
            Boolean.toString(floxNriPluginEnabled));
    return env;
  }

  public Map<String, Object> toOutputMap() {
    final Map<String, Object> outputs =
        Map.of(
            "policyDebugKdnsEnabled",
            kdnsEnabled,
            "policyDebugKdnsSuspend",
            kdnsSuspend,
            "policyDebugKdnsFloxEnvironment",
            kdnsFloxEnvironment(),
            "policyDebugFloxNriPluginEnabled",
            floxNriPluginEnabled);
    return outputs;
  }
}
