// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.flox;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterRuntimeNamespaceManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitContext;
import io.nxmatic.rk2lab.manifests.layers.replication.ReplicationReplicatorManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.runtime.daemonset.RuntimeDaemonsetScriptPolicyManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class FloxRuntimeManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "runtime/flox-runtime";

  public FloxRuntimeManifestUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(
            ClusterRuntimeNamespaceManifestUnit.MANIFEST_UNIT_ID,
            ReplicationReplicatorManifestUnit.MANIFEST_UNIT_ID,
            RuntimeDaemonsetScriptPolicyManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new FloxRuntimeLayer(chart, "layer-runtime-flox-runtime");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new FloxRuntimeLayer(context.chart(), "layer-runtime-flox-runtime", context.registry());
  }
}
