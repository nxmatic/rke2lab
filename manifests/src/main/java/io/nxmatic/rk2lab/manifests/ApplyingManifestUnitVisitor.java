// @codebase
package io.nxmatic.rk2lab.manifests;

public final class ApplyingManifestUnitVisitor implements ManifestUnitVisitor {

  @Override
  public void visit(final ManifestUnit manifestUnit, final ManifestUnitContext context) {
    manifestUnit.apply(context);
  }
}
