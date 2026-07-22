// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.contract.ManifestDomainPolicy;

/**
 * A domain's contribution of manifest units to the synthesis — the unit channel of the three
 * contribution channels (units here, node-env via {@code NodeEnvContributor}, host-assets via
 * {@code HostAssetProvider}); see {@code
 * docs/architecture/manifests/manifests-contribution-channels.adoc}. Each implementation is an SCR
 * {@code @Component(service = ManifestsDomainRegistrar.class)} discovered through the OSGi registry
 * and collected by {@code DefaultManifestSynthesisService} (a {@code @Reference(MULTIPLE)}), which
 * calls {@link #domain(ManifestDomainPolicy)} on each at synthesis time and builds the {@link
 * ManifestsDomainRegistry} from the results — no static registration.
 *
 * <p>Because the run policy arrives as the {@link #domain(ManifestDomainPolicy)} argument (not as
 * construction state), a registrar is a stateless singleton. Most contribute a fixed set of units
 * by overriding {@link #domain()}; a policy-aware registrar overrides {@link
 * #domain(ManifestDomainPolicy)} to include optional units conditionally.
 *
 * <pre>{@code
 * @Component(service = ManifestsDomainRegistrar.class)
 * public final class GitopsDomainRegistrar implements ManifestsDomainRegistrar {
 *   @Override
 *   public ManifestsDomain domain() {
 *     return new ManifestsDomain(ManifestDomainCatalog.GITOPS, List.of(...), units);
 *   }
 * }
 * }</pre>
 *
 * @see ManifestDomainPolicy
 * @see ManifestsDomain
 */
public interface ManifestsDomainRegistrar {

  ManifestsDomain domain();

  default ManifestsDomain domain(ManifestDomainPolicy policy) {
    return domain();
  }
}
