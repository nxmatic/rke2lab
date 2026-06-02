// @codebase
package io.nxmatic.rk2lab.manifests;

import java.util.ArrayList;
import java.util.List;

public final class LayerDomainRegistryBuilder {

  private final List<LayerDomain> domains = new ArrayList<>();

  public LayerDomainRegistryBuilder register(final LayerDomainRegistrar registrar) {
    return registerDomain(registrar.domain());
  }

  public LayerDomainRegistryBuilder register(
      final LayerDomainRegistrar registrar, final ManifestDomainPolicy policy) {
    return registerDomain(registrar.domain(policy));
  }

  public LayerDomainRegistryBuilder registerDomain(final LayerDomain domain) {
    domains.add(domain);
    return this;
  }

  public LayerDomainRegistry build() {
    return new LayerDomainRegistry(List.copyOf(domains));
  }
}
