// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class CiliumAdvancedManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "networking/cilium-advanced";
  public static final String LEGACY_PATH_PREFIX = "networking/cilium-advanced/";

  public CiliumAdvancedManifestUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(LEGACY_PATH_PREFIX),
        List.of(CiliumConfigManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new CiliumAdvancedLayer(chart, "layer-networking-cilium-advanced");
  }
}
