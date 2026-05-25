// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.FloxDebugPolicy;
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

  /**
   * Single accessor for the flox NRI debug toggle. Layers reach this through their owning manifest
   * unit; the policy is published by the synthesizer for the duration of one {@code synthesize}
   * call via {@link ManifestSynthesisContext}.
   */
  protected final FloxDebugPolicy floxDebugPolicy() {
    return ManifestSynthesisContext.current().floxDebugPolicy();
  }
}
