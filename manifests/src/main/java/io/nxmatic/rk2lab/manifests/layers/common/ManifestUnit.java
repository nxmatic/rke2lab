// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.List;
import org.cdk8s.Chart;

public interface ManifestUnit {

  String manifestUnitId();

  List<String> legacyPathPrefixes();

  List<String> dependsOnManifestUnitIds();

  void apply(Chart chart);
}
