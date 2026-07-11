package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.units.networking.CiliumAdvancedManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.networking.CiliumConfigManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.networking.EnvoyGatewayManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.networking.KdnsManifestsUnit;
import java.util.List;

public final class NetworkingDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.NETWORKING,
        List.of(ManifestDomainCatalog.CLUSTER),
        List.of(
            new CiliumConfigManifestsUnit(),
            new CiliumAdvancedManifestsUnit(),
            new EnvoyGatewayManifestsUnit(),
            new KdnsManifestsUnit()));
  }
}
