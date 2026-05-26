// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.daemonset;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class RuntimeDaemonsetScriptPolicyAssets {

  private static final String RESOURCE_ROOT = "/runtime/daemonset/.sh.d";
  private final Class<?> resourceAnchor;
  private final List<ScriptAsset> scriptAssets;

  private RuntimeDaemonsetScriptPolicyAssets(Builder builder) {
    this.resourceAnchor = builder.resourceAnchor;
    this.scriptAssets = List.copyOf(builder.scriptAssets);
  }

  public static Builder builder() {
    return new Builder();
  }

  public Map<String, String> configMapData() {
    final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    for (ScriptAsset asset : scriptAssets) {
      data.put(
          asset.configMapKey(), normalizeConfigMapText(readResource(asset.classpathResource())));
    }
    return Map.copyOf(data);
  }

  public Map<String, String> relativePathsByKey() {
    final LinkedHashMap<String, String> paths = new LinkedHashMap<>();
    for (ScriptAsset asset : scriptAssets) {
      paths.put(asset.configMapKey(), asset.relativePath());
    }
    return Map.copyOf(paths);
  }

  public Object[] volumeItems() {
    return scriptAssets.stream()
        .map(asset -> Map.of("key", asset.configMapKey(), "path", asset.relativePath()))
        .toArray();
  }

  private String readResource(String resourcePath) {
    try (InputStream input = resourceAnchor.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Missing runtime daemonset resource: " + resourcePath);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed reading runtime daemonset resource: " + resourcePath, ex);
    }
  }

  private static String normalizeConfigMapText(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }

    final String normalizedLineEndings = value.replace("\r\n", "\n").replace('\r', '\n');
    if (normalizedLineEndings.endsWith("\n")) {
      return normalizedLineEndings;
    }
    return normalizedLineEndings + "\n";
  }

  public static final class Builder {
    private Class<?> resourceAnchor = RuntimeDaemonsetScriptPolicyAssets.class;
    private final List<ScriptAsset> scriptAssets = new ArrayList<>();

    private Builder() {
      addDefaultScriptAssets();
    }

    public Builder addDefaultScriptAssets() {
      addScriptAsset(
          asset ->
              asset
                  .configMapKey("daemonset-logging.sh")
                  .classpathResource(RESOURCE_ROOT + "/daemonset-logging.sh")
                  .relativePath(".sh.d/daemonset-logging.sh"));
      addScriptAsset(
          asset ->
              asset
                  .configMapKey("daemonless-host-asset-materializer.sh")
                  .classpathResource(RESOURCE_ROOT + "/daemonless-host-asset-materializer.sh")
                  .relativePath(".sh.d/daemonless-host-asset-materializer.sh"));
      addScriptAsset(
          asset ->
              asset
                  .configMapKey("daemonless-host-shell-policy.sh")
                  .classpathResource(RESOURCE_ROOT + "/daemonless-host-shell-policy.sh")
                  .relativePath(".sh.d/daemonless-host-shell-policy.sh"));
      addScriptAsset(
          asset ->
              asset
                  .configMapKey("daemonless-trampoline.sh")
                  .classpathResource(RESOURCE_ROOT + "/daemonless-trampoline.sh")
                  .relativePath(".sh.d/daemonless-trampoline.sh"));
      addScriptAsset(
          asset ->
              asset
                  .configMapKey("daemonless-host-asset-reconciler.sh")
                  .classpathResource(RESOURCE_ROOT + "/daemonless-host-asset-reconciler.sh")
                  .relativePath(".sh.d/daemonless-host-asset-reconciler.sh"));
      return this;
    }

    public Builder resourceAnchor(Class<?> resourceAnchor) {
      this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
      return this;
    }

    public Builder addScriptAsset(Consumer<ScriptAsset.Builder> scriptAssetBuilder) {
      Objects.requireNonNull(scriptAssetBuilder, "scriptAssetBuilder");
      final ScriptAsset.Builder builder = ScriptAsset.builder();
      scriptAssetBuilder.accept(builder);
      return addScriptAsset(builder.build());
    }

    public Builder addScriptAsset(ScriptAsset scriptAsset) {
      scriptAssets.add(Objects.requireNonNull(scriptAsset, "scriptAsset"));
      return this;
    }

    public Builder clearScriptAssets() {
      scriptAssets.clear();
      return this;
    }

    public RuntimeDaemonsetScriptPolicyAssets build() {
      return new RuntimeDaemonsetScriptPolicyAssets(this);
    }
  }

  public static final class ScriptAsset {
    private final String configMapKey;
    private final String classpathResource;
    private final String relativePath;

    private ScriptAsset(Builder builder) {
      this.configMapKey = Objects.requireNonNull(builder.configMapKey, "configMapKey");
      this.classpathResource =
          Objects.requireNonNull(builder.classpathResource, "classpathResource");
      this.relativePath = Objects.requireNonNull(builder.relativePath, "relativePath");
    }

    public static Builder builder() {
      return new Builder();
    }

    public String configMapKey() {
      return configMapKey;
    }

    public String classpathResource() {
      return classpathResource;
    }

    public String relativePath() {
      return relativePath;
    }

    public static final class Builder {
      private String configMapKey;
      private String classpathResource;
      private String relativePath;

      private Builder() {}

      public Builder configMapKey(String configMapKey) {
        this.configMapKey = configMapKey;
        return this;
      }

      public Builder classpathResource(String classpathResource) {
        this.classpathResource = classpathResource;
        return this;
      }

      public Builder relativePath(String relativePath) {
        this.relativePath = relativePath;
        return this;
      }

      public ScriptAsset build() {
        return new ScriptAsset(this);
      }
    }
  }
}
