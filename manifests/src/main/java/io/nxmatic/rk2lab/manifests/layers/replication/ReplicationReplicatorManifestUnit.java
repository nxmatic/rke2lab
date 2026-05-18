// @codebase
package io.nxmatic.rk2lab.manifests.layers.replication;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class ReplicationReplicatorManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "replication/replicator";

  public ReplicationReplicatorManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new ReplicatorLayer(chart, "layer-replication-replicator");
  }
}
