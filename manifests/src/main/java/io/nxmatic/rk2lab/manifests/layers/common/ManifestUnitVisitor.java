// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

public interface ManifestUnitVisitor {

  void visit(ManifestUnit manifestUnit, Chart chart);
}
