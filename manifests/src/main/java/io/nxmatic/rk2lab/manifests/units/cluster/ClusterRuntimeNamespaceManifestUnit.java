// @codebase
package io.nxmatic.rk2lab.manifests.units.cluster;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.ManifestUnitContext;
import java.util.List;
import org.cdk8s.Chart;

public final class ClusterRuntimeNamespaceManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.CLUSTER + "/runtime-system-namespace";

  public ClusterRuntimeNamespaceManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new ClusterRuntimeNamespaceComponent(chart, "layer-cluster-runtime-system-namespace");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new ClusterRuntimeNamespaceComponent(
        context.chart(), "layer-cluster-runtime-system-namespace", context.registry());
  }
}
