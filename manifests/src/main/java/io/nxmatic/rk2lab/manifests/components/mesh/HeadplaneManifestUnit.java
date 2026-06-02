// @codebase
package io.nxmatic.rk2lab.manifests.components.mesh;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.ManifestUnitContext;
import java.util.List;
import org.cdk8s.Chart;

public final class HeadplaneManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.MESH + "/headplane";

  public HeadplaneManifestUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(
            MeshSystemNamespaceManifestUnit.MANIFEST_UNIT_ID,
            HeadscaleManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new HeadplaneComponent(chart, "layer-mesh-headplane");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new HeadplaneComponent(context.chart(), "layer-mesh-headplane", context.registry());
  }
}
