// @codebase
package io.nxmatic.rk2lab.manifests.units.networking;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import java.util.List;
import org.cdk8s.Chart;

public final class CiliumAdvancedManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.NETWORKING + "/cilium-advanced";

  public CiliumAdvancedManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(CiliumConfigManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new CiliumAdvancedComponent(chart, "layer-networking-cilium-advanced");
  }
}
