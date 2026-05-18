// @codebase
package io.nxmatic.rk2lab.manifests.layers.networking;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KdnsAssets {

  private static final String RESOURCE_ROOT = "/runtime/networking/kdns";

  private static final List<ConfigMapAsset> DLV_SCRIPT_ASSETS =
      List.of(asset("kdns-dlv.sh", RESOURCE_ROOT + "/kdns-dlv.sh"));

  private KdnsAssets() {}

  public static Map<String, String> dlvScriptConfigMapData() {
    final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    for (ConfigMapAsset asset : DLV_SCRIPT_ASSETS) {
      data.put(asset.configMapKey(), readResource(asset.classpathResource()));
    }
    return Map.copyOf(data);
  }

  private static ConfigMapAsset asset(String configMapKey, String classpathResource) {
    return new ConfigMapAsset(configMapKey, classpathResource);
  }

  private static String readResource(String resourcePath) {
    try (InputStream input = KdnsAssets.class.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Missing kdns resource: " + resourcePath);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed reading kdns resource: " + resourcePath, ex);
    }
  }

  private record ConfigMapAsset(String configMapKey, String classpathResource) {}
}
