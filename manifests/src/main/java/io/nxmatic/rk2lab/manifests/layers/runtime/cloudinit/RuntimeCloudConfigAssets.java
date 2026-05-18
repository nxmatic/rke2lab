// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.cloudinit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeCloudConfigAssets {

  private static final String RESOURCE_ROOT = "/runtime/cloud-config";

  private static final List<ConfigMapAsset> CONFIGMAP_ASSETS =
      List.of(
          asset("userData", RESOURCE_ROOT + "/user-data"),
          asset("metaData", RESOURCE_ROOT + "/meta-data"),
          asset("networkData", RESOURCE_ROOT + "/network-config"));

  private RuntimeCloudConfigAssets() {}

  public static Map<String, String> configMapData() {
    final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    for (ConfigMapAsset asset : CONFIGMAP_ASSETS) {
      data.put(asset.configMapKey(), readResource(asset.classpathResource()));
    }
    return Map.copyOf(data);
  }

  private static ConfigMapAsset asset(String configMapKey, String classpathResource) {
    return new ConfigMapAsset(configMapKey, classpathResource);
  }

  private static String readResource(String resourcePath) {
    try (InputStream input = RuntimeCloudConfigAssets.class.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Missing runtime cloud-config resource: " + resourcePath);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed reading runtime cloud-config resource: " + resourcePath, ex);
    }
  }

  private record ConfigMapAsset(String configMapKey, String classpathResource) {}
}
