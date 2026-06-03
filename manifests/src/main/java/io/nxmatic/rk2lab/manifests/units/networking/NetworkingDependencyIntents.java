// @codebase
package io.nxmatic.rk2lab.manifests.units.networking;

import io.nxmatic.rk2lab.manifests.profiles.LayerDependencyIntentProfile;
import java.util.List;
import java.util.Map;

public final class NetworkingDependencyIntents {

  private final String requiresCiliumConfigIntent;

  private final LayerDependencyIntentProfile profile;

  private NetworkingDependencyIntents(Builder builder) {
    this.requiresCiliumConfigIntent = builder.requiresCiliumConfigIntent;
    this.profile =
        new LayerDependencyIntentProfile(
            Map.of(this.requiresCiliumConfigIntent, List.of(builder.ciliumConfigManifestUnitId)));
  }

  public static Builder builder() {
    return new Builder();
  }

  public String requiresCiliumConfigIntent() {
    return requiresCiliumConfigIntent;
  }

  public List<String> resolve(final List<String> intents) {
    return profile.resolve(intents);
  }

  public static final class Builder {
    private String requiresCiliumConfigIntent = "networking:requires-cilium-config";
    private String ciliumConfigManifestUnitId = "networking/cilium-config";

    private Builder() {}

    public Builder requiresCiliumConfigIntent(String requiresCiliumConfigIntent) {
      this.requiresCiliumConfigIntent = requiresCiliumConfigIntent;
      return this;
    }

    public Builder ciliumConfigManifestUnitId(String ciliumConfigManifestUnitId) {
      this.ciliumConfigManifestUnitId = ciliumConfigManifestUnitId;
      return this;
    }

    public NetworkingDependencyIntents build() {
      return new NetworkingDependencyIntents(this);
    }
  }
}
