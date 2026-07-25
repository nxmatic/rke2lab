package io.nxmatic.rke2lab.manifests.contract.node;

import io.nxmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.contract.profiles.HostPaths;
import io.nxmatic.rke2lab.manifests.contract.profiles.NetworkTopology;

/**
 * Read-only context passed to node-env contributors. A thin composite of the three narrow views
 * synth consumes — identity, network topology, host paths — plus the run's manifest-domain policy.
 * Each consumer depends on the one view that concerns it (reach the composite, consume a narrow
 * view), so there is no fat accessor surface to reach across.
 */
public interface NodeEnvContext {

  /** Host filesystem path slice ({@code /srv/host} directories). */
  HostPaths hostPaths();

  /** Cross-cutting identity slice (cluster + node names/ids, token, domain, incus remote). */
  BootstrapIdentity bootstrapIdentity();

  /** Cluster network topology slice (CIDRs, interfaces, addresses, blueprint MACs). */
  NetworkTopology networkTopology();

  /**
   * The run's manifest-domain decision (which layers are enabled). Carried here so a node-env
   * contributor can derive the {@code RKE2LAB_MANIFESTS_PUBLISH_*} vars from the same run-scoped
   * policy that drives the synth-time domain filter.
   */
  ManifestDomainPolicy manifestDomainPolicy();
}
