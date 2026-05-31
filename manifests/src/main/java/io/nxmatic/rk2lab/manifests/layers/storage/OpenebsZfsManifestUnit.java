// @codebase
package io.nxmatic.rk2lab.manifests.layers.storage;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class OpenebsZfsManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.STORAGE + "/openebs-zfs";

  public OpenebsZfsManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new OpenebsZfsLayer(chart, "layer-storage-openebs-zfs", componentVersions().openebsZfsChart());
  }
}
