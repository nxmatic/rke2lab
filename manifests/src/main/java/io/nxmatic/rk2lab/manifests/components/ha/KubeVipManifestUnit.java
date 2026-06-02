// @codebase
package io.nxmatic.rk2lab.manifests.components.ha;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import java.util.List;
import org.cdk8s.Chart;

public final class KubeVipManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.HIGH_AVAILABILITY + "/kube-vip";

  public KubeVipManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new KubeVipComponent(chart, "layer-high-availability-kube-vip", componentVersions().kubeVip());
  }
}
