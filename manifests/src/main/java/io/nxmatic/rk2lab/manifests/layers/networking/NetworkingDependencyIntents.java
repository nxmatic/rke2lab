// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.LayerDependencyIntentProfile;
import java.util.List;
import java.util.Map;

public final class NetworkingDependencyIntents {

  public static final String REQUIRES_CILIUM_CONFIG = "networking:requires-cilium-config";

  private static final LayerDependencyIntentProfile PROFILE =
      new LayerDependencyIntentProfile(
          Map.of(REQUIRES_CILIUM_CONFIG, List.of("networking/cilium-config")));

  private NetworkingDependencyIntents() {}

  public static List<String> resolve(final List<String> intents) {
    return PROFILE.resolve(intents);
  }
}
