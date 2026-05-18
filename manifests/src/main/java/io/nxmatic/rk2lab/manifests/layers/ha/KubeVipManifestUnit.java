// @codebase
package io.nxmatic.rk2lab.manifests.layers.ha;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class KubeVipManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "high-availability/kube-vip";
  public static final String PATH_PREFIX = "high-availability/kube-vip/";

  public KubeVipManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of(PATH_PREFIX), List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new KubeVipLayer(chart, "layer-high-availability-kube-vip");
  }
}
