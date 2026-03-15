// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

public final class ApplyingLayerVisitor implements LayerVisitor {

    @Override
    public void visit(final ModeledLayer layer, final Chart chart) {
        layer.apply(chart);
    }
}
