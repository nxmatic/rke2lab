// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

public final class ApplyingManifestUnitVisitor implements ManifestUnitVisitor {

    @Override
    public void visit(final ManifestUnit manifestUnit, final Chart chart) {
        manifestUnit.apply(chart);
    }
}
