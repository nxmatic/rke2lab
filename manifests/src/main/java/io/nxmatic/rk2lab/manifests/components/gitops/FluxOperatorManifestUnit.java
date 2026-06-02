// @codebase
package io.nxmatic.rk2lab.manifests.components.gitops;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import java.util.List;
import org.cdk8s.Chart;

public final class FluxOperatorManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/flux-operator";

  public FluxOperatorManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new FluxOperatorComponent(
        chart, "layer-gitops-flux-operator", componentVersions().fluxOperator());
  }
}
