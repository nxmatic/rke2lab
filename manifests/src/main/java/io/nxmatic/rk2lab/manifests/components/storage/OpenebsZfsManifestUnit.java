// @codebase
package io.nxmatic.rk2lab.manifests.components.storage;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import java.util.List;
import org.cdk8s.Chart;

public final class OpenebsZfsManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.STORAGE + "/openebs-zfs";

  public OpenebsZfsManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new OpenebsZfsComponent(
        chart, "layer-storage-openebs-zfs", componentVersions().openebsZfsChart());
  }
}
