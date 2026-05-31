// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget;

/**
 * Context passed to systemd unit synthesis, containing references to common targets and the domain
 * catalog.
 *
 * <p>This solves the lifecycle/ordering problem: targets are created FIRST by the orchestrator,
 * then passed to all domains so they can reference them without hard-coded strings.
 *
 * <p><b>Pattern</b>: Create once, pass everywhere.
 *
 * @param rke2labTarget the main rke2lab.target (parent of all rke2lab services)
 * @param bootstrapTarget the rke2lab-bootstrap.target (early bootstrap, pre-server)
 * @param manifestsTarget the rke2lab-manifests.target (manifest installers, post-server)
 * @param secretsTarget the rke2lab-secrets.target (secrets installers, post-server)
 * @param networkTarget the rke2lab-network.target (networking infrastructure)
 * @param toolsTarget the rke2lab-tools.target (tools and utilities)
 * @param domainCatalog the shared domain catalog (single source of truth for domain IDs)
 */
public record SystemdSynthesisContext(
    SystemdTarget rke2labTarget,
    SystemdTarget bootstrapTarget,
    SystemdTarget manifestsTarget,
    SystemdTarget secretsTarget,
    SystemdTarget networkTarget,
    SystemdTarget toolsTarget,
    ManifestDomainCatalog domainCatalog) {

  public SystemdSynthesisContext {
    if (rke2labTarget == null) {
      throw new IllegalArgumentException("rke2labTarget must not be null");
    }
    if (bootstrapTarget == null) {
      throw new IllegalArgumentException("bootstrapTarget must not be null");
    }
    if (manifestsTarget == null) {
      throw new IllegalArgumentException("manifestsTarget must not be null");
    }
    if (secretsTarget == null) {
      throw new IllegalArgumentException("secretsTarget must not be null");
    }
    if (networkTarget == null) {
      throw new IllegalArgumentException("networkTarget must not be null");
    }
    if (toolsTarget == null) {
      throw new IllegalArgumentException("toolsTarget must not be null");
    }
    if (domainCatalog == null) {
      throw new IllegalArgumentException("domainCatalog must not be null");
    }
  }
}
