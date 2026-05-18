// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.profiles;

import java.util.List;
import java.util.Optional;

public final class DelveSidecarToggleResolver {

  private static final String GLOBAL_KEY = "rk2lab.debug.sidecar.enabled";
  private static final String DOMAIN_KEY_PREFIX = "rk2lab.debug.sidecar.domain.";
  private static final String LAYER_KEY_PREFIX = "rk2lab.debug.sidecar.layer.";

  private DelveSidecarToggleResolver() {}

  public static boolean resolveByDomainLayer(
      final String domain, final String layer, final boolean defaultValue) {
    String domainNormalized = sanitize(domain);
    String layerNormalized = sanitize(layer);

    String domainKey = DOMAIN_KEY_PREFIX + domainNormalized + ".enabled";
    String layerKey = LAYER_KEY_PREFIX + domainNormalized + "." + layerNormalized + ".enabled";

    return firstDefinedBoolean(List.of(layerKey, domainKey, GLOBAL_KEY)).orElse(defaultValue);
  }

  private static Optional<Boolean> firstDefinedBoolean(final List<String> keys) {
    for (String key : keys) {
      String value = System.getProperty(key);
      if (value != null && !value.isBlank()) {
        return Optional.of(Boolean.parseBoolean(value.trim()));
      }
    }
    return Optional.empty();
  }

  private static String sanitize(final String value) {
    return value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9]+", ".");
  }
}
