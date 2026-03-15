// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import org.cdk8s.Chart;

public interface LayerVisitor {

    void visit(ModeledLayer layer, Chart chart);
}
