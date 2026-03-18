// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

public interface ManifestUnitVisitor {

  void visit(ManifestUnit manifestUnit, ManifestUnitContext context);
}
