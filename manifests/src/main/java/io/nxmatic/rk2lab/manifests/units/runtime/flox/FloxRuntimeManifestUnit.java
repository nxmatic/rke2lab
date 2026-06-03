// @codebase
package io.nxmatic.rk2lab.manifests.units.runtime.flox;

import io.nxmatic.rk2lab.manifests.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.ManifestUnitContext;
import io.nxmatic.rk2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestUnit;
import io.nxmatic.rk2lab.manifests.units.platform.ReplicatorManifestUnit;
import io.nxmatic.rk2lab.manifests.units.runtime.daemonset.RuntimeDaemonsetScriptPolicyManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class FloxRuntimeManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/flox";

  public FloxRuntimeManifestUnit() {
    super(
        MANIFEST_UNIT_ID,
        List.of(
            ClusterRuntimeNamespaceManifestUnit.MANIFEST_UNIT_ID,
            ReplicatorManifestUnit.MANIFEST_UNIT_ID,
            RuntimeDaemonsetScriptPolicyManifestUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public void apply(final Chart chart) {
    new FloxRuntimeComponent(chart, "layer-runtime-flox-runtime");
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    new FloxRuntimeComponent(context.chart(), "layer-runtime-flox-runtime", context.registry());
  }
}
