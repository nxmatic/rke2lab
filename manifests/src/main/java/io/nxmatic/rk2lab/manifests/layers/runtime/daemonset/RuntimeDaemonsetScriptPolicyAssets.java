// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.daemonset;

import io.nxmatic.rk2lab.manifests.EmbeddedAsset;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeDaemonsetScriptPolicyAssets {

  private static final String RESOURCE_ROOT = "/runtime/daemonset/.sh.d";

  private static final List<ScriptAsset> SCRIPT_ASSETS =
      List.of(
          asset(
              "daemonset-logging.sh",
              RESOURCE_ROOT + "/daemonset-logging.sh",
              ".sh.d/daemonset-logging.sh"),
          asset(
              "daemonless-host-asset-materializer.sh",
              RESOURCE_ROOT + "/daemonless-host-asset-materializer.sh",
              ".sh.d/daemonless-host-asset-materializer.sh"),
          asset(
              "daemonless-host-shell-policy.sh",
              RESOURCE_ROOT + "/daemonless-host-shell-policy.sh",
              ".sh.d/daemonless-host-shell-policy.sh"),
          asset(
              "daemonless-trampoline.sh",
              RESOURCE_ROOT + "/daemonless-trampoline.sh",
              ".sh.d/daemonless-trampoline.sh"));

  private RuntimeDaemonsetScriptPolicyAssets() {}

  public static Map<String, String> configMapData() {
    final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    for (ScriptAsset asset : SCRIPT_ASSETS) {
      data.put(asset.configMapKey(), readResource(asset.classpathResource()));
    }
    return Map.copyOf(data);
  }

  public static Map<String, String> relativePathsByKey() {
    final LinkedHashMap<String, String> paths = new LinkedHashMap<>();
    for (ScriptAsset asset : SCRIPT_ASSETS) {
      paths.put(asset.configMapKey(), asset.relativePath());
    }
    return Map.copyOf(paths);
  }

  public static Object[] volumeItems() {
    return SCRIPT_ASSETS.stream()
        .map(asset -> Map.of("key", asset.configMapKey(), "path", asset.relativePath()))
        .toArray();
  }

  public static List<EmbeddedAsset> materializationAssets() {
    return SCRIPT_ASSETS.stream()
        .map(asset -> new EmbeddedAsset(asset.classpathResource(), asset.relativePath(), false))
        .toList();
  }

  private static ScriptAsset asset(
      String configMapKey, String classpathResource, String relativePath) {
    return new ScriptAsset(configMapKey, classpathResource, relativePath);
  }

  private static String readResource(String resourcePath) {
    try (InputStream input =
        RuntimeDaemonsetScriptPolicyAssets.class.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Missing runtime daemonset resource: " + resourcePath);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed reading runtime daemonset resource: " + resourcePath, ex);
    }
  }

  private record ScriptAsset(String configMapKey, String classpathResource, String relativePath) {}
}
