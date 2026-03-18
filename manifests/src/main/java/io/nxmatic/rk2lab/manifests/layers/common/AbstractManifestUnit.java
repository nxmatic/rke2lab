// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import java.util.List;

public abstract class AbstractManifestUnit implements ManifestUnit {

  private final String manifestUnitId;
  private final List<String> legacyPathPrefixes;
  private final List<String> dependsOnManifestUnitIds;

  protected AbstractManifestUnit(
      final String manifestUnitId,
      final List<String> legacyPathPrefixes,
      final List<String> dependsOnManifestUnitIds) {
    this.manifestUnitId = manifestUnitId;
    this.legacyPathPrefixes = List.copyOf(legacyPathPrefixes);
    this.dependsOnManifestUnitIds = List.copyOf(dependsOnManifestUnitIds);
  }

  @Override
  public final String manifestUnitId() {
    return manifestUnitId;
  }

  @Override
  public final List<String> legacyPathPrefixes() {
    return legacyPathPrefixes;
  }

  @Override
  public final List<String> dependsOnManifestUnitIds() {
    return dependsOnManifestUnitIds;
  }
}
