package io.nxmatic.rk2lab.manifests.layers.mesh;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitContext;
import java.util.List;
import org.cdk8s.Chart;

public final class MeshSystemNamespaceManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "mesh/system-namespace";

  public MeshSystemNamespaceManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new MeshSystemNamespaceLayer(chart, "layer-mesh-system-namespace");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new MeshSystemNamespaceLayer(
        context.chart(), "layer-mesh-system-namespace", context.registry());
  }
}
