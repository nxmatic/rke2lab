// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.List;

public abstract class AbstractManifestUnit implements ManifestUnit {

  private final String manifestUnitId;
  private final List<String> dependsOnManifestUnitIds;

  protected AbstractManifestUnit(
      final String manifestUnitId, final List<String> dependsOnManifestUnitIds) {
    this.manifestUnitId = manifestUnitId;
    this.dependsOnManifestUnitIds = List.copyOf(dependsOnManifestUnitIds);
  }

  @Override
  public final String manifestUnitId() {
    return manifestUnitId;
  }

  @Override
  public final List<String> dependsOnManifestUnitIds() {
    return dependsOnManifestUnitIds;
  }
}
