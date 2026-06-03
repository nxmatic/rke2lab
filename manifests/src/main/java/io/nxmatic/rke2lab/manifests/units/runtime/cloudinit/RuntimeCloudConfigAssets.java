// @codebase
package io.nxmatic.rke2lab.manifests.units.runtime.cloudinit;

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

public final class RuntimeCloudConfigAssets {

  private static final String RESOURCE_ROOT = "/runtime/cloud-config";

  private final Class<?> resourceAnchor;
  private final List<ConfigMapAsset> configMapAssets;

  private RuntimeCloudConfigAssets(Builder builder) {
    this.resourceAnchor = builder.resourceAnchor;
    this.configMapAssets = List.copyOf(builder.configMapAssets);
  }

  public static Builder builder() {
    return new Builder();
  }

  public Map<String, String> configMapData() {
    final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    for (ConfigMapAsset asset : configMapAssets) {
      data.put(asset.configMapKey(), readResource(asset.classpathResource()));
    }
    return Map.copyOf(data);
  }

  private String readResource(String resourcePath) {
    try (InputStream input = resourceAnchor.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Missing runtime cloud-config resource: " + resourcePath);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed reading runtime cloud-config resource: " + resourcePath, ex);
    }
  }

  public static final class Builder {
    private Class<?> resourceAnchor = RuntimeCloudConfigAssets.class;
    private final List<ConfigMapAsset> configMapAssets = new ArrayList<>();

    private Builder() {
      addDefaultConfigMapAssets();
    }

    public Builder resourceAnchor(Class<?> resourceAnchor) {
      this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
      return this;
    }

    public Builder addDefaultConfigMapAssets() {
      addConfigMapAsset("userData", RESOURCE_ROOT + "/user-data");
      addConfigMapAsset("metaData", RESOURCE_ROOT + "/meta-data");
      addConfigMapAsset("networkData", RESOURCE_ROOT + "/network-config");
      return this;
    }

    public Builder addConfigMapAsset(String configMapKey, String classpathResource) {
      return addConfigMapAsset(
          ConfigMapAsset.builder()
              .configMapKey(configMapKey)
              .classpathResource(classpathResource)
              .build());
    }

    public Builder addConfigMapAsset(Consumer<ConfigMapAsset.Builder> configMapAssetBuilder) {
      Objects.requireNonNull(configMapAssetBuilder, "configMapAssetBuilder");
      ConfigMapAsset.Builder builder = ConfigMapAsset.builder();
      configMapAssetBuilder.accept(builder);
      return addConfigMapAsset(builder.build());
    }

    public Builder addConfigMapAsset(ConfigMapAsset configMapAsset) {
      configMapAssets.add(Objects.requireNonNull(configMapAsset, "configMapAsset"));
      return this;
    }

    public Builder clearConfigMapAssets() {
      configMapAssets.clear();
      return this;
    }

    public RuntimeCloudConfigAssets build() {
      return new RuntimeCloudConfigAssets(this);
    }
  }

  public static final class ConfigMapAsset {
    private final String configMapKey;
    private final String classpathResource;

    private ConfigMapAsset(Builder builder) {
      this.configMapKey = Objects.requireNonNull(builder.configMapKey, "configMapKey");
      this.classpathResource =
          Objects.requireNonNull(builder.classpathResource, "classpathResource");
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

    public static final class Builder {
      private String configMapKey;
      private String classpathResource;

      private Builder() {}

      public Builder configMapKey(String configMapKey) {
        this.configMapKey = configMapKey;
        return this;
      }

      public Builder classpathResource(String classpathResource) {
        this.classpathResource = classpathResource;
        return this;
      }

      public ConfigMapAsset build() {
        return new ConfigMapAsset(this);
      }
    }
  }
}
