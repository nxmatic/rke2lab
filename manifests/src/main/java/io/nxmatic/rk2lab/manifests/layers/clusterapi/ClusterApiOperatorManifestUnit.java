package io.nxmatic.rk2lab.manifests.layers.clusterapi;

import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import org.cdk8s.Chart;

public final class ClusterApiOperatorManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "clusterapi/operator";

  public ClusterApiOperatorManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final Chart chart) {
    new ClusterApiOperatorLayer(
        chart,
        "layer-clusterapi-operator",
        componentVersions().clusterApiOperator(),
        componentVersions().clusterApiOperator(),
        componentVersions().capiIncusProvider(),
        componentVersions().capiRke2Provider());
  }
}
