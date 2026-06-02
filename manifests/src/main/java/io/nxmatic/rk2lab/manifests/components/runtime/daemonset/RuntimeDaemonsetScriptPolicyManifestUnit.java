// @codebase
package io.nxmatic.rk2lab.manifests.components.runtime.daemonset;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.ManifestUnitContext;
import io.nxmatic.rk2lab.manifests.components.cluster.ClusterRuntimeNamespaceManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class RuntimeDaemonsetScriptPolicyManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/daemonset";

  public RuntimeDaemonsetScriptPolicyManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(ClusterRuntimeNamespaceManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new RuntimeDaemonsetScriptPolicyComponent(chart, "layer-runtime-daemonset");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new RuntimeDaemonsetScriptPolicyComponent(
        context.chart(), "layer-runtime-daemonset", context.registry());
  }
}
