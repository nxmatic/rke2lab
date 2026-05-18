// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitContext;
import java.util.List;
import org.cdk8s.Chart;

public final class HeadscaleManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "mesh/headscale";

  public HeadscaleManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(MeshSystemNamespaceManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new HeadscaleLayer(chart, "layer-mesh-headscale");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new HeadscaleLayer(context.chart(), "layer-mesh-headscale", context.registry());
  }
}
