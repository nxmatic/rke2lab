package io.nxmatic.rke2lab.manifests.contract.node;

import io.nxmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.contract.profiles.NetworkTopology;

/**
 * Read-only context exposing the run's node views to synth-time units — the cluster/node identity
 * and the network topology, derived once from the handed-over identity. Each consumer depends on
 * the one view that concerns it (reach the composite, consume a narrow view), so there is no fat
 * accessor surface to reach across.
 */
public interface NodeEnvContext {

  /** Cross-cutting identity slice (cluster + node names/ids, token, domain, incus remote). */
  BootstrapIdentity bootstrapIdentity();

  /** Cluster network topology slice (CIDRs, interfaces, addresses, blueprint MACs). */
  NetworkTopology networkTopology();
}
