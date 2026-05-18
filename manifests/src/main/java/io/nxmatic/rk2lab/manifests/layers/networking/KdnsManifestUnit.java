// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterRuntimeNamespaceManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import java.util.List;
import java.util.stream.Stream;
import org.cdk8s.Chart;

public final class KdnsManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID = "networking/kdns";

  private static final NetworkingDependencyIntents NETWORKING_DEPENDENCY_INTENTS =
      NetworkingDependencyIntents.builder().build();

  public KdnsManifestUnit() {
    super(
        MANIFEST_UNIT_ID,
        Stream.concat(
                NETWORKING_DEPENDENCY_INTENTS
                    .resolve(List.of(NETWORKING_DEPENDENCY_INTENTS.requiresCiliumConfigIntent()))
                    .stream(),
                Stream.of(ClusterRuntimeNamespaceManifestUnit.MANIFEST_UNIT_ID))
            .toList());
  }

  @Override
  public void apply(final Chart chart) {
    new KdnsLayer(chart, "layer-networking-kdns");
  }
}
