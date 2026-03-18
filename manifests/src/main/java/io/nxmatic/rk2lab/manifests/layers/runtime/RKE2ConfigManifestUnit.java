// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class RKE2ConfigManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "runtime/rke2-config";

  public RKE2ConfigManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(RuntimeRke2ConfigLayer.LEGACY_PATH_PREFIX), List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new RuntimeRke2ConfigLayer(chart, "layer-runtime-rke2-config");
  }
}
