package io.nxmatic.rk2lab.controlplane.policy;

import java.util.Map;

/** Debug policy for controlplane-managed runtime features. */
public record DebugPolicy(boolean kdnsEnabled, boolean floxShimWrapperEnabled) {

  public String kdnsFloxEnvironment() {
    return "nxmatic/kdns";
  }

  public String kdnsRuntimeClass() {
    return kdnsEnabled ? "flox-delve" : "flox";
  }

  public Map<String, String> toEnvMap() {
    final Map<String, String> env =
        Map.of(
            "RKE2LAB_POLICY_DEBUG_KDNS_ENABLED",
            Boolean.toString(kdnsEnabled),
            "RKE2LAB_POLICY_DEBUG_KDNS_FLOX_ENV",
            kdnsFloxEnvironment(),
            "RKE2LAB_POLICY_DEBUG_KDNS_RUNTIME_CLASS",
            kdnsRuntimeClass(),
            "RKE2LAB_POLICY_DEBUG_FLOX_SHIM_WRAPPER_ENABLED",
            Boolean.toString(floxShimWrapperEnabled));
    return env;
  }

  public Map<String, Object> toOutputMap() {
    final Map<String, Object> outputs =
        Map.of(
            "policyDebugKdnsEnabled",
            kdnsEnabled,
            "policyDebugKdnsFloxEnvironment",
            kdnsFloxEnvironment(),
            "policyDebugKdnsRuntimeClass",
            kdnsRuntimeClass(),
            "policyDebugFloxShimWrapperEnabled",
            floxShimWrapperEnabled);
    return outputs;
  }
}
