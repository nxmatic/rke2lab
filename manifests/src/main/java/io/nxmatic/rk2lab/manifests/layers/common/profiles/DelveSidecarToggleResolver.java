// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.profiles;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class DelveSidecarToggleResolver {

  private final String globalKey;
  private final String domainKeyPrefix;
  private final String layerKeyPrefix;
  private final Function<String, String> propertyLookup;

  private DelveSidecarToggleResolver(Builder builder) {
    this.globalKey = builder.globalKey;
    this.domainKeyPrefix = builder.domainKeyPrefix;
    this.layerKeyPrefix = builder.layerKeyPrefix;
    this.propertyLookup = builder.propertyLookup;
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean resolveByDomainLayer(
      final String domain, final String layer, final boolean defaultValue) {
    String domainNormalized = sanitize(domain);
    String layerNormalized = sanitize(layer);

    String domainKey = domainKeyPrefix + domainNormalized + ".enabled";
    String layerKey = layerKeyPrefix + domainNormalized + "." + layerNormalized + ".enabled";

    return firstDefinedBoolean(List.of(layerKey, domainKey, globalKey)).orElse(defaultValue);
  }

  private Optional<Boolean> firstDefinedBoolean(final List<String> keys) {
    for (String key : keys) {
      String value = propertyLookup.apply(key);
      if (value != null && !value.isBlank()) {
        return Optional.of(Boolean.parseBoolean(value.trim()));
      }
    }
    return Optional.empty();
  }

  private static String sanitize(final String value) {
    return value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9]+", ".");
  }

  public static final class Builder {
    private String globalKey = "rk2lab.debug.sidecar.enabled";
    private String domainKeyPrefix = "rk2lab.debug.sidecar.domain.";
    private String layerKeyPrefix = "rk2lab.debug.sidecar.layer.";
    private Function<String, String> propertyLookup = System::getProperty;

    private Builder() {}

    public Builder globalKey(String globalKey) {
      this.globalKey = globalKey;
      return this;
    }

    public Builder domainKeyPrefix(String domainKeyPrefix) {
      this.domainKeyPrefix = domainKeyPrefix;
      return this;
    }

    public Builder layerKeyPrefix(String layerKeyPrefix) {
      this.layerKeyPrefix = layerKeyPrefix;
      return this;
    }

    public Builder propertyLookup(Function<String, String> propertyLookup) {
      this.propertyLookup = propertyLookup;
      return this;
    }

    public DelveSidecarToggleResolver build() {
      return new DelveSidecarToggleResolver(this);
    }
  }
}
