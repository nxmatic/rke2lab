// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterRuntimeNamespaceManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitContext;
import io.nxmatic.rk2lab.manifests.layers.replication.ReplicationReplicatorManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class FloxContainerdShimManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "runtime/flox-containerd-shim";

  public FloxContainerdShimManifestUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(FloxContainerdShimLayer.LEGACY_PATH_PREFIX),
        List.of(
            ClusterRuntimeNamespaceManifestUnit.MANIFEST_UNIT_ID,
            ReplicationReplicatorManifestUnit.MANIFEST_UNIT_ID,
            RuntimeDaemonsetScriptPolicyManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new FloxContainerdShimLayer(chart, "layer-runtime-flox-containerd-shim");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new FloxContainerdShimLayer(
        context.chart(), "layer-runtime-flox-containerd-shim", context.registry());
  }
}
