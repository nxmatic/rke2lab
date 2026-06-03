// @codebase
package io.nxmatic.rk2lab.manifests.units.cicd;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import java.util.List;
import org.cdk8s.Chart;

public final class TektonPipelinesManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/tekton-pipelines";

  public TektonPipelinesManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new TektonPipelinesComponent(
        chart, "layer-cicd-tekton-pipelines", componentVersions().tektonOperator());
  }
}
