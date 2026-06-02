package io.nxmatic.rk2lab.manifests.components.platform;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import java.util.List;
import org.cdk8s.Chart;

public final class CertManagerManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.PLATFORM + "/cert-manager";

  public CertManagerManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new CertManagerComponent(
        chart, "layer-platform-cert-manager", componentVersions().certManager());
  }
}
