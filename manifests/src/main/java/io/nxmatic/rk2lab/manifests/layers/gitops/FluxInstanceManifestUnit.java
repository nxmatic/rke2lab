// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class FluxInstanceManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "gitops/flux-instance";

  public FluxInstanceManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(FluxInstanceLayer.LEGACY_PATH_PREFIX), List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new FluxInstanceLayer(chart, "layer-gitops-flux-instance");
  }
}
