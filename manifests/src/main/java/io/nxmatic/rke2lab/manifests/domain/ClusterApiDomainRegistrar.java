package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.ManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.clusterapi.ClusterApiOperatorManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.clusterapi.ImageStateConfigMapManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.clusterapi.IncusIdentitySecretManifestsUnit;
import java.util.List;

public final class ClusterApiDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.CLUSTER_API,
        List.of(),
        List.of(
            ManifestsUnit.lazy(
                IncusIdentitySecretManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                IncusIdentitySecretManifestsUnit::new),
            ManifestsUnit.lazy(
                ImageStateConfigMapManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                ImageStateConfigMapManifestsUnit::new),
            ManifestsUnit.lazy(
                ClusterApiOperatorManifestsUnit.MANIFEST_UNIT_ID,
                List.of(),
                ClusterApiOperatorManifestsUnit::new)));
  }
}
