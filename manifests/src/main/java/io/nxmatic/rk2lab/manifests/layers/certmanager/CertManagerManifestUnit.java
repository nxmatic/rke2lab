package io.nxmatic.rk2lab.manifests.layers.certmanager;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class CertManagerManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.CERT_MANAGER + "/cert-manager";

  public CertManagerManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new CertManagerLayer(chart, "layer-cert-manager", componentVersions().certManager());
  }
}
