package io.nxmatic.rke2lab.controlplane.policy;

import java.util.Map;

/** Network policy for controlplane-managed runtime networking behavior. */
public record NetworkPolicy(boolean lanBindingEnabled) {

  public Map<String, String> toEnvMap() {
    return Map.of(
        "RKE2LAB_POLICY_NETWORK_LAN_BINDING_ENABLED", Boolean.toString(lanBindingEnabled));
  }

  public Map<String, Object> toOutputMap() {
    return Map.of("policyNetworkLanBindingEnabled", lanBindingEnabled);
  }
}
