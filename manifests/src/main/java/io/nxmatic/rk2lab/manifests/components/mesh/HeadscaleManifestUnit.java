// @codebase
package io.nxmatic.rk2lab.manifests.components.mesh;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.ManifestUnitContext;
import java.util.List;
import org.cdk8s.Chart;

public final class HeadscaleManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.MESH + "/headscale";

  public HeadscaleManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(MeshSystemNamespaceManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new HeadscaleComponent(chart, "layer-mesh-headscale");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new HeadscaleComponent(context.chart(), "layer-mesh-headscale", context.registry());
  }
}
