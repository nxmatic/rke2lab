// @codebase
package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class HeadplaneManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "mesh/headplane";
  public static final String LEGACY_PATH_PREFIX = "mesh/headplane/";

  public HeadplaneManifestUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(LEGACY_PATH_PREFIX),
        List.of(HeadscaleManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new HeadplaneLayer(chart, "layer-mesh-headplane");
  }
}
