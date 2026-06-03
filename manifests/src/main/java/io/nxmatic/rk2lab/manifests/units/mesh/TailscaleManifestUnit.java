// @codebase
package io.nxmatic.rk2lab.manifests.units.mesh;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.ManifestUnitContext;
import java.util.List;
import org.cdk8s.Chart;

public final class TailscaleManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.MESH + "/tailscale";

  public TailscaleManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(MeshSystemNamespaceManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new TailscaleComponent(chart, "layer-mesh-tailscale");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new TailscaleComponent(context.chart(), "layer-mesh-tailscale", context.registry());
  }
}
