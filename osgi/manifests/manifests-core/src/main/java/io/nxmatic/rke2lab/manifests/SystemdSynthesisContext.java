// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget;
import io.nxmatic.rke2lab.manifests.port.ManifestDomainCatalog;

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
 * @param cniManifestsTarget the rke2lab-cni-manifests.target (installers gated on CNI readiness)
 * @param operatorManifestsTarget the rke2lab-operator-manifests.target (installers gated on an
 *     operator's CRDs)
 * @param secretsTarget the rke2lab-secrets.target (secrets installers, post-server)
 * @param networkTarget the rke2lab-network.target (networking infrastructure)
 * @param toolsTarget the rke2lab-tools.target (tools and utilities)
 * @param domainCatalog the shared domain catalog (single source of truth for domain IDs)
 */
public record SystemdSynthesisContext(
    SystemdTarget rke2labTarget,
    SystemdTarget bootstrapTarget,
    SystemdTarget manifestsTarget,
    SystemdTarget cniManifestsTarget,
    SystemdTarget operatorManifestsTarget,
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
    if (cniManifestsTarget == null) {
      throw new IllegalArgumentException("cniManifestsTarget must not be null");
    }
    if (operatorManifestsTarget == null) {
      throw new IllegalArgumentException("operatorManifestsTarget must not be null");
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

  /**
   * The target an installer for {@code phase} should be {@code WantedBy}/{@code PartOf}. The phase
   * selects among the pre-created targets; it never creates one (see
   * docs/rke2-install-phases.adoc).
   */
  public SystemdTarget targetFor(InstallPhase phase) {
    return switch (phase) {
      case PRE_SERVER -> bootstrapTarget;
      case POST_SERVER -> manifestsTarget;
      case POST_CNI_READY -> cniManifestsTarget;
      case POST_OPERATOR_READY -> operatorManifestsTarget;
    };
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Named builder for the seven targets + catalog. Positional construction is error-prone here —
   * seven of the components share the {@link SystemdTarget} type, so a swapped pair would not be
   * caught by the compiler.
   */
  public static final class Builder {
    private SystemdTarget rke2labTarget;
    private SystemdTarget bootstrapTarget;
    private SystemdTarget manifestsTarget;
    private SystemdTarget cniManifestsTarget;
    private SystemdTarget operatorManifestsTarget;
    private SystemdTarget secretsTarget;
    private SystemdTarget networkTarget;
    private SystemdTarget toolsTarget;
    private ManifestDomainCatalog domainCatalog;

    private Builder() {}

    public Builder rke2labTarget(SystemdTarget t) {
      this.rke2labTarget = t;
      return this;
    }

    public Builder bootstrapTarget(SystemdTarget t) {
      this.bootstrapTarget = t;
      return this;
    }

    public Builder manifestsTarget(SystemdTarget t) {
      this.manifestsTarget = t;
      return this;
    }

    public Builder cniManifestsTarget(SystemdTarget t) {
      this.cniManifestsTarget = t;
      return this;
    }

    public Builder operatorManifestsTarget(SystemdTarget t) {
      this.operatorManifestsTarget = t;
      return this;
    }

    public Builder secretsTarget(SystemdTarget t) {
      this.secretsTarget = t;
      return this;
    }

    public Builder networkTarget(SystemdTarget t) {
      this.networkTarget = t;
      return this;
    }

    public Builder toolsTarget(SystemdTarget t) {
      this.toolsTarget = t;
      return this;
    }

    public Builder domainCatalog(ManifestDomainCatalog c) {
      this.domainCatalog = c;
      return this;
    }

    public SystemdSynthesisContext build() {
      return new SystemdSynthesisContext(
          rke2labTarget,
          bootstrapTarget,
          manifestsTarget,
          cniManifestsTarget,
          operatorManifestsTarget,
          secretsTarget,
          networkTarget,
          toolsTarget,
          domainCatalog);
    }
  }
}
