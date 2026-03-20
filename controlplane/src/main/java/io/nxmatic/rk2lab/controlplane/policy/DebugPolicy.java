package io.nxmatic.rk2lab.controlplane.policy;

import java.util.Map;

/** Debug policy for controlplane-managed runtime features. */
public record DebugPolicy(boolean kdnsEnabled, boolean floxShimWrapperEnabled) {

  public String kdnsFloxEnvironment() {
    return kdnsEnabled ? "nxmatic/kdns-debug" : "nxmatic/kdns";
  }

  public Map<String, String> toEnvMap() {
    return Map.of(
        "RKE2LAB_POLICY_DEBUG_KDNS_ENABLED", Boolean.toString(kdnsEnabled),
        "RKE2LAB_POLICY_DEBUG_KDNS_FLOX_ENV", kdnsFloxEnvironment(),
        "RKE2LAB_POLICY_DEBUG_FLOX_SHIM_WRAPPER_ENABLED", Boolean.toString(floxShimWrapperEnabled));
  }

  public Map<String, Object> toOutputMap() {
    return Map.of(
        "policyDebugKdnsEnabled", kdnsEnabled,
        "policyDebugKdnsFloxEnvironment", kdnsFloxEnvironment(),
        "policyDebugFloxShimWrapperEnabled", floxShimWrapperEnabled);
  }
}
