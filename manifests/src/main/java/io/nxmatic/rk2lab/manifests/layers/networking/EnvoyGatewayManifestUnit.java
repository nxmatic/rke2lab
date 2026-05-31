// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class EnvoyGatewayManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.NETWORKING + "/envoy-gateway";

  public EnvoyGatewayManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(CiliumAdvancedManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new EnvoyGatewayLayer(
        chart, "layer-networking-envoy-gateway", componentVersions().envoyGateway());
  }
}
