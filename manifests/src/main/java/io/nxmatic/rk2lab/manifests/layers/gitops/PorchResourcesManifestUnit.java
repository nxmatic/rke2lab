// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class PorchResourcesManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/porch-resources";

  public PorchResourcesManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(FluxInstanceManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new PorchResourcesLayer(chart, "layer-gitops-porch-resources", bootstrapIdentity());
  }
}
