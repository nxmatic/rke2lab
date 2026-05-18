// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.env;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class RKE2LabEnvConfigManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "runtime/env-config";

  public RKE2LabEnvConfigManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(RuntimeEnvConfigLayer.LEGACY_PATH_PREFIX), List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new RuntimeEnvConfigLayer(chart, "layer-runtime-env-config");
  }
}
