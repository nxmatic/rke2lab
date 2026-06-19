// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.port.ManifestDomainPolicy;
import java.util.ArrayList;
import java.util.List;

public final class ManifestsDomainRegistryBuilder {

  private final List<ManifestsDomain> domains = new ArrayList<>();

  public ManifestsDomainRegistryBuilder register(final ManifestsDomainRegistrar registrar) {
    return registerDomain(registrar.domain());
  }

  public ManifestsDomainRegistryBuilder register(
      final ManifestsDomainRegistrar registrar, final ManifestDomainPolicy policy) {
    return registerDomain(registrar.domain(policy));
  }

  public ManifestsDomainRegistryBuilder registerDomain(final ManifestsDomain domain) {
    domains.add(domain);
    return this;
  }

  public ManifestsDomainRegistry build() {
    return new ManifestsDomainRegistry(List.copyOf(domains));
  }
}
