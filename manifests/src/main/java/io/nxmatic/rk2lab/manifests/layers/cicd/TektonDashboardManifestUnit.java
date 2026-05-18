// @codebase
package io.nxmatic.rk2lab.manifests.layers.cicd;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class TektonDashboardManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "cicd/tekton-dashboard";

  public TektonDashboardManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(TektonPipelinesManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new TektonDashboardLayer(chart, "layer-cicd-tekton-dashboard");
  }
}
