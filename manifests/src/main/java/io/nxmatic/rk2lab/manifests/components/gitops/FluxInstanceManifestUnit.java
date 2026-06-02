// @codebase
package io.nxmatic.rk2lab.manifests.components.gitops;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import java.util.List;
import org.cdk8s.Chart;

public final class FluxInstanceManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/flux-instance";

  public FluxInstanceManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(FluxOperatorManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new FluxInstanceComponent(
        chart, "layer-gitops-flux-instance", componentVersions().fluxOperator());
  }
}
