// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.List;
import org.cdk8s.Chart;

public interface ManifestUnit {

  String manifestUnitId();

  List<String> dependsOnManifestUnitIds();

  default void apply(ManifestUnitContext context) {
    apply(context.chart());
  }

  default void apply(Chart chart) {
    throw new UnsupportedOperationException(
        "ManifestUnit must override apply(Chart) or apply(ManifestUnitContext): "
            + manifestUnitId());
  }
}
