// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.cloudinit;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class CloudConfigManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/cloud-config";

  public CloudConfigManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new RuntimeCloudConfigLayer(chart, "layer-runtime-cloud-config");
  }
}
