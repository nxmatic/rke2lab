package io.nxmatic.rke2lab.manifests.domain;

import io.nxmatic.rke2lab.manifests.ManifestsDomain;
import io.nxmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.units.clusterapi.ClusterApiOperatorManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.clusterapi.ImageStateConfigMapManifestsUnit;
import io.nxmatic.rke2lab.manifests.units.clusterapi.IncusIdentitySecretManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

@Component(service = ManifestsDomainRegistrar.class)
public final class ClusterApiDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.CLUSTER_API,
        List.of(),
        List.of(
            new IncusIdentitySecretManifestsUnit(),
            new ImageStateConfigMapManifestsUnit(),
            new ClusterApiOperatorManifestsUnit()));
  }
}
