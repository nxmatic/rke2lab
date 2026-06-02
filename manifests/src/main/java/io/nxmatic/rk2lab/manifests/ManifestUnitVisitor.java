// @codebase
package io.nxmatic.rk2lab.manifests;

public interface ManifestUnitVisitor {

  void visit(ManifestUnit manifestUnit, ManifestUnitContext context);
}
