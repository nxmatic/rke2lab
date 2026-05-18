// @codebase
package io.nxmatic.rk2lab.manifests.layers.cluster;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitContext;
import java.util.List;
import org.cdk8s.Chart;

public final class ClusterRuntimeNamespaceManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "cluster/runtime-system-namespace";

  public ClusterRuntimeNamespaceManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new ClusterRuntimeNamespaceLayer(chart, "layer-cluster-runtime-system-namespace");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new ClusterRuntimeNamespaceLayer(
        context.chart(), "layer-cluster-runtime-system-namespace", context.registry());
  }
}
