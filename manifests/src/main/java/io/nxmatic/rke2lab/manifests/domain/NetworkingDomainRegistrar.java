package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
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
        List.of(),
        List.of(
            ManifestsUnit.lazy(
                CiliumConfigManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                CiliumConfigManifestsUnit::new),
            ManifestsUnit.lazy(
                CiliumAdvancedManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                CiliumAdvancedManifestsUnit::new),
            ManifestsUnit.lazy(
                EnvoyGatewayManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                EnvoyGatewayManifestsUnit::new),
            ManifestsUnit.lazy(
                KdnsManifestsUnit.MANIFEST_UNIT_ID, List.of(), KdnsManifestsUnit::new)));
  }
}
