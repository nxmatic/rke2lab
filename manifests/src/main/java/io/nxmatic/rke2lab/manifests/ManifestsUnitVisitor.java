// @codebase
package io.nxmatic.rke2lab.manifests;

public interface ManifestsUnitVisitor {

  void visit(ManifestsUnit manifestUnit, ManifestsUnitContext context);
}
