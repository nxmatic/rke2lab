// @codebase
package io.nxmatic.rke2lab.manifests.profiles;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class LayerDependencyIntentProfile {

  private final Map<String, List<String>> intentToLayerIds;

  public LayerDependencyIntentProfile(final Map<String, List<String>> intentToLayerIds) {
    this.intentToLayerIds = Map.copyOf(intentToLayerIds);
  }

  public List<String> resolve(final List<String> intents) {
    LinkedHashSet<String> ordered = new LinkedHashSet<>();
    for (String intent : intents) {
      List<String> deps = intentToLayerIds.get(intent);
      if (deps == null) {
        throw new IllegalArgumentException("Unknown dependency intent: " + intent);
      }
      ordered.addAll(deps);
    }
    return List.copyOf(new ArrayList<>(ordered));
  }
}
