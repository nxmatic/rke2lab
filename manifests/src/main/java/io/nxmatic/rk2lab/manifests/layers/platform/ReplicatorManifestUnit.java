package io.nxmatic.rk2lab.manifests.layers.platform;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class ReplicatorManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.PLATFORM + "/replicator";

  public ReplicatorManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new ReplicatorLayer(
        chart, "layer-platform-replicator", componentVersions().kubernetesReplicator());
  }
}
