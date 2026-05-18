// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.daemonset;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterRuntimeNamespaceManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitContext;
import java.util.List;
import org.cdk8s.Chart;

public final class RuntimeDaemonsetScriptPolicyManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "runtime/daemonset";

  public RuntimeDaemonsetScriptPolicyManifestUnit() {
    super(
        MANIFEST_UNIT_ID, List.of(), List.of(ClusterRuntimeNamespaceManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new RuntimeDaemonsetScriptPolicyLayer(chart, "layer-runtime-daemonset");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new RuntimeDaemonsetScriptPolicyLayer(
        context.chart(), "layer-runtime-daemonset", context.registry());
  }
}
