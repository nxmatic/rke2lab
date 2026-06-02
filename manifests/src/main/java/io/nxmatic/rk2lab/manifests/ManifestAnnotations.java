// @codebase
package io.nxmatic.rk2lab.manifests;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata annotations for rke2lab manifest resources.
 *
 * <p>These annotations enable manifest exploding (splitting consolidated YAML into per-resource
 * files organized by layer/package).
 */
public final class ManifestAnnotations {

  public static final String LAYER = "io.nxmatic.rke2lab/layer";
  public static final String PACKAGE = "io.nxmatic.rke2lab/package";

  public Map<String, String> packageAnnotations(final String layer, final String packageName) {
    return packageAnnotations(layer, packageName, Map.of());
  }

  public Map<String, String> packageAnnotations(
      final String layer, final String packageName, final Map<String, String> extraAnnotations) {
    LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(LAYER, layer);
    annotations.put(PACKAGE, packageName);
    annotations.putAll(extraAnnotations);
    return Map.copyOf(annotations);
  }
}
