// @codebase
package io.nxmatic.rk2lab.manifests.components.cicd;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import java.util.List;
import org.cdk8s.Chart;

public final class TektonDashboardManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/tekton-dashboard";

  public TektonDashboardManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(TektonPipelinesManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new TektonDashboardComponent(chart, "layer-cicd-tekton-dashboard");
  }
}
