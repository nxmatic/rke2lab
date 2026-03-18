// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class RuntimeDaemonsetScriptPolicyManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "runtime/daemonset";

  public RuntimeDaemonsetScriptPolicyManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(), List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new RuntimeDaemonsetScriptPolicyLayer(chart, "layer-runtime-daemonset");
  }
}
