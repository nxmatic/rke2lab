// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class KdnsManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "networking/kdns";

  public KdnsManifestUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(KdnsLayer.LEGACY_PATH_PREFIX),
        NetworkingDependencyIntents.resolve(
            List.of(NetworkingDependencyIntents.REQUIRES_CILIUM_CONFIG)));
  }

  @Override
  public void apply(final Chart chart) {
    new KdnsLayer(chart, "layer-networking-kdns");
  }
}
