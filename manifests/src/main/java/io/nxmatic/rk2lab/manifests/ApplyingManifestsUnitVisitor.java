// @codebase
package io.nxmatic.rk2lab.manifests;

public final class ApplyingManifestsUnitVisitor implements ManifestsUnitVisitor {

  @Override
  public void visit(final ManifestsUnit manifestUnit, final ManifestsUnitContext context) {
    manifestUnit.apply(context);
  }
}
