// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class CiliumConfigManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "networking/cilium-config";

  public CiliumConfigManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new CiliumConfigLayer(chart, "layer-networking-cilium-config");
  }
}
