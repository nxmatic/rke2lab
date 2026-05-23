// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.flox;

import io.nxmatic.rk2lab.manifests.EmbeddedAsset;
import io.nxmatic.rk2lab.manifests.layers.runtime.daemonset.RuntimeDaemonsetScriptPolicyAssets;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class FloxContainerdShimAssets {

  private static final String FLOX_RESOURCE_ROOT = "/runtime/containerd-shim-flox";

  private final Class<?> resourceAnchor;
  private final List<EmbeddedAsset> floxMaterializationAssets;
  private final List<InstallerAsset> floxInstallerConfigMapAssets;
  private final RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets;
  private final WrapperGoArchiveAssets wrapperGoArchiveAssets;

  private FloxContainerdShimAssets(Builder builder) {
    this.resourceAnchor = builder.resourceAnchor;
    this.floxMaterializationAssets = List.copyOf(builder.floxMaterializationAssets);
    this.floxInstallerConfigMapAssets = List.copyOf(builder.floxInstallerConfigMapAssets);
    this.runtimeDaemonsetScriptPolicyAssets = builder.runtimeDaemonsetScriptPolicyAssets;
    this.wrapperGoArchiveAssets = builder.wrapperGoArchiveAssets;
  }

  public static Builder builder() {
    return new Builder();
  }

  public List<EmbeddedAsset> materializationAssets() {
    final ArrayList<EmbeddedAsset> assets = new ArrayList<>(floxMaterializationAssets);
    assets.addAll(runtimeDaemonsetScriptPolicyAssets.materializationAssets());
    return List.copyOf(assets);
  }

  public Path worktreeShimAssetsRelativePath() {
    return Path.of("manifests", "src", "main", "resources", "runtime", "containerd-shim-flox");
  }

  public Map<String, String> installerConfigMapData() {
    final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    for (InstallerAsset asset : floxInstallerConfigMapAssets) {
      data.put(
          asset.configMapKey(), normalizeConfigMapText(readResource(asset.classpathResource())));
    }
    data.putAll(runtimeDaemonsetScriptPolicyAssets.configMapData());
    data.put(WrapperGoArchiveAssets.ARCHIVE_CONFIGMAP_KEY, wrapperGoArchiveAssets.archiveBase64());
    data.put(WrapperGoArchiveAssets.MANIFEST_CONFIGMAP_KEY, wrapperGoArchiveAssets.manifestJson());
    return Map.copyOf(data);
  }

  public Object[] installerVolumeItems() {
    final LinkedHashMap<String, String> archiveItem =
        new LinkedHashMap<>(
            Map.of(
                "key",
                WrapperGoArchiveAssets.ARCHIVE_CONFIGMAP_KEY,
                "path",
                "build-assets/" + WrapperGoArchiveAssets.ARCHIVE_CONFIGMAP_KEY));
    final LinkedHashMap<String, String> manifestItem =
        new LinkedHashMap<>(
            Map.of(
                "key",
                WrapperGoArchiveAssets.MANIFEST_CONFIGMAP_KEY,
                "path",
                "build-assets/" + WrapperGoArchiveAssets.MANIFEST_CONFIGMAP_KEY));

    final List<Map<String, String>> items =
        floxInstallerConfigMapAssets.stream()
            .map(asset -> Map.of("key", asset.configMapKey(), "path", asset.mountPath()))
            .toList();

    final List<Map<String, String>> daemonsetItems =
        runtimeDaemonsetScriptPolicyAssets.relativePathsByKey().entrySet().stream()
            .map(
                entry ->
                    Map.of(
                        "key", entry.getKey(), "path", installerBuildAssetPath(entry.getValue())))
            .toList();

    final Object[] out = new Object[items.size() + daemonsetItems.size() + 2];
    for (int i = 0; i < items.size(); i++) {
      out[i] = items.get(i);
    }
    for (int i = 0; i < daemonsetItems.size(); i++) {
      out[items.size() + i] = daemonsetItems.get(i);
    }
    out[items.size() + daemonsetItems.size()] = archiveItem;
    out[items.size() + daemonsetItems.size() + 1] = manifestItem;
    return out;
  }

  public void materializeSupplementaryAssetsTo(Path outputDir) {
    try {
      wrapperGoArchiveAssets.materializeTo(outputDir);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed to materialize Flox supplementary assets to " + outputDir, ex);
    }
  }

  private static String installerBuildAssetPath(String relativePath) {
    return "build-assets/" + relativePath;
  }

  private String readResource(String resourcePath) {
    try (InputStream input = resourceAnchor.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Missing containerd-shim-flox resource: " + resourcePath);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed reading containerd-shim-flox resource: " + resourcePath, ex);
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
    private Class<?> resourceAnchor = FloxContainerdShimAssets.class;
    private final List<EmbeddedAsset> floxMaterializationAssets = new ArrayList<>();
    private final List<InstallerAsset> floxInstallerConfigMapAssets = new ArrayList<>();
    private RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets =
        RuntimeDaemonsetScriptPolicyAssets.builder().build();
    private WrapperGoArchiveAssets wrapperGoArchiveAssets =
        WrapperGoArchiveAssets.builder().build();

    private Builder() {
      addDefaultMaterializationAssets();
      addDefaultInstallerAssets();
    }

    public Builder addDefaultMaterializationAssets() {
      addDefaultCoreMaterializationAssets();
      addDefaultDebugToolsMaterializationAssets();
      addDefaultMeshHeadplaneMaterializationAssets();
      addDefaultNetworkingKdnsMaterializationAssets();
      return this;
    }

    public Builder addDefaultCoreMaterializationAssets() {
      addMaterializationAsset(FLOX_RESOURCE_ROOT + "/shim-build.sh", "shim-build.sh", true);
      addMaterializationAsset(FLOX_RESOURCE_ROOT + "/shim-build.yaml", "shim-build.yaml", false);
      addMaterializationAsset(FLOX_RESOURCE_ROOT + "/flake.nix", "flake.nix", false);
      addMaterializationAsset(FLOX_RESOURCE_ROOT + "/shim-installer.sh", "shim-installer.sh", true);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/bin/flox-rootfs-sync.sh", "bin/flox-rootfs-sync.sh", true);
      return this;
    }

    public Builder addDefaultDebugToolsMaterializationAssets() {
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/debug-tools/.sh.d/rke2lab-debug-tooling.sh",
          "debug-tools/.sh.d/rke2lab-debug-tooling.sh",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/debug-tools/attach_live_containerd_shim_flox_v2_strace.sh",
          "debug-tools/attach_live_containerd_shim_flox_v2_strace.sh",
          true);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/debug-tools/crictl-kdns-repro.sh",
          "debug-tools/crictl-kdns-repro.sh",
          true);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-bundle-watch.sh",
          "debug-tools/kdns-containerd-bundle-watch.sh",
          true);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-remote-capture.sh",
          "debug-tools/kdns-containerd-remote-capture.sh",
          true);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/debug-tools/master-shim-pprof.sh",
          "debug-tools/master-shim-pprof.sh",
          true);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/debug-tools/rke2lab-dlv.sh", "debug-tools/rke2lab-dlv.sh", true);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/debug-tools/rke2lab-shim-dlv.sh",
          "debug-tools/rke2lab-shim-dlv.sh",
          true);
      return this;
    }

    public Builder addDefaultMeshHeadplaneMaterializationAssets() {
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/mesh/headplane/flake.nix", "mesh/headplane/flake.nix", false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/.gitattributes",
          "mesh/headplane/.flox/.gitattributes",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/.gitignore",
          "mesh/headplane/.flox/.gitignore",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env.json",
          "mesh/headplane/.flox/env.json",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env.lock",
          "mesh/headplane/.flox/env.lock",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env/manifest.toml",
          "mesh/headplane/.flox/env/manifest.toml",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env/manifest.lock",
          "mesh/headplane/.flox/env/manifest.lock",
          false);
      return this;
    }

    public Builder addDefaultNetworkingKdnsMaterializationAssets() {
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/networking/kdns/flake.nix", "networking/kdns/flake.nix", false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/.gitattributes",
          "networking/kdns/.flox/.gitattributes",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/.gitignore",
          "networking/kdns/.flox/.gitignore",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env.json",
          "networking/kdns/.flox/env.json",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env.lock",
          "networking/kdns/.flox/env.lock",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env/manifest.toml",
          "networking/kdns/.flox/env/manifest.toml",
          false);
      addMaterializationAsset(
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env/manifest.lock",
          "networking/kdns/.flox/env/manifest.lock",
          false);
      return this;
    }

    public Builder addDefaultInstallerAssets() {
      addDefaultCoreInstallerAssets();
      addDefaultDebugToolsInstallerAssets();
      addDefaultFlakeInstallerAssets();
      return this;
    }

    public Builder addDefaultCoreInstallerAssets() {

      addInstallerAsset(
          "shim-installer.sh", FLOX_RESOURCE_ROOT + "/shim-installer.sh", "bin/shim-installer.sh");
      addInstallerAsset(
          "shim-build.sh", FLOX_RESOURCE_ROOT + "/shim-build.sh", "build-assets/bin/shim-build.sh");
      addInstallerAsset(
          "shim-build.yaml",
          FLOX_RESOURCE_ROOT + "/shim-build.yaml",
          "build-assets/shim-build.yaml");
      addInstallerAsset(
          "runtime-flake.nix", FLOX_RESOURCE_ROOT + "/flake.nix", "build-assets/flake.nix");
      addInstallerAsset(
          "flox-rootfs-sync.sh",
          FLOX_RESOURCE_ROOT + "/bin/flox-rootfs-sync.sh",
          "build-assets/bin/flox-rootfs-sync.sh");
      return this;
    }

    public Builder addDefaultDebugToolsInstallerAssets() {
      addInstallerAsset(
          "debug-tools-rke2lab-debug-tooling.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/.sh.d/rke2lab-debug-tooling.sh",
          "build-assets/debug-tools/.sh.d/rke2lab-debug-tooling.sh");
      addInstallerAsset(
          "debug-tools-attach-live-containerd-shim-flox-v2-strace.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/attach_live_containerd_shim_flox_v2_strace.sh",
          "build-assets/debug-tools/attach_live_containerd_shim_flox_v2_strace.sh");
      addInstallerAsset(
          "debug-tools-crictl-kdns-repro.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/crictl-kdns-repro.sh",
          "build-assets/debug-tools/crictl-kdns-repro.sh");
      addInstallerAsset(
          "debug-tools-kdns-containerd-bundle-watch.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-bundle-watch.sh",
          "build-assets/debug-tools/kdns-containerd-bundle-watch.sh");
      addInstallerAsset(
          "debug-tools-kdns-containerd-remote-capture.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-remote-capture.sh",
          "build-assets/debug-tools/kdns-containerd-remote-capture.sh");
      addInstallerAsset(
          "debug-tools-master-shim-pprof.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/master-shim-pprof.sh",
          "build-assets/debug-tools/master-shim-pprof.sh");
      addInstallerAsset(
          "debug-tools-rke2lab-dlv.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/rke2lab-dlv.sh",
          "build-assets/debug-tools/rke2lab-dlv.sh");
      addInstallerAsset(
          "debug-tools-rke2lab-shim-dlv.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/rke2lab-shim-dlv.sh",
          "build-assets/debug-tools/rke2lab-shim-dlv.sh");
      return this;
    }

    public Builder addDefaultFlakeInstallerAssets() {
      addInstallerAsset(
          "mesh-headplane-flake.nix",
          FLOX_RESOURCE_ROOT + "/mesh/headplane/flake.nix",
          "build-assets/mesh/headplane/flake.nix");
      addInstallerAsset(
          "networking-kdns-flake.nix",
          FLOX_RESOURCE_ROOT + "/networking/kdns/flake.nix",
          "build-assets/networking/kdns/flake.nix");
      // kdns flox environment files (note: .gitignore/.gitattributes excluded by Maven resource filtering)
      addInstallerAsset(
          "networking-kdns-flox-env-json",
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env.json",
          "build-assets/networking/kdns/.flox/env.json");
      addInstallerAsset(
          "networking-kdns-flox-env-lock",
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env.lock",
          "build-assets/networking/kdns/.flox/env.lock");
      addInstallerAsset(
          "networking-kdns-flox-env-manifest-toml",
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env/manifest.toml",
          "build-assets/networking/kdns/.flox/env/manifest.toml");
      addInstallerAsset(
          "networking-kdns-flox-env-manifest-lock",
          FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env/manifest.lock",
          "build-assets/networking/kdns/.flox/env/manifest.lock");
      // mesh/headplane flox environment files (note: .gitignore/.gitattributes excluded by Maven resource filtering)
      addInstallerAsset(
          "mesh-headplane-flox-env-json",
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env.json",
          "build-assets/mesh/headplane/.flox/env.json");
      addInstallerAsset(
          "mesh-headplane-flox-env-lock",
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env.lock",
          "build-assets/mesh/headplane/.flox/env.lock");
      addInstallerAsset(
          "mesh-headplane-flox-env-manifest-toml",
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env/manifest.toml",
          "build-assets/mesh/headplane/.flox/env/manifest.toml");
      addInstallerAsset(
          "mesh-headplane-flox-env-manifest-lock",
          FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env/manifest.lock",
          "build-assets/mesh/headplane/.flox/env/manifest.lock");
      return this;
    }

    public Builder resourceAnchor(Class<?> resourceAnchor) {
      this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
      return this;
    }

    public Builder runtimeDaemonsetScriptPolicyAssets(
        RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets) {
      this.runtimeDaemonsetScriptPolicyAssets =
          Objects.requireNonNull(
              runtimeDaemonsetScriptPolicyAssets, "runtimeDaemonsetScriptPolicyAssets");
      return this;
    }

    public Builder wrapperGoArchiveAssets(WrapperGoArchiveAssets wrapperGoArchiveAssets) {
      this.wrapperGoArchiveAssets =
          Objects.requireNonNull(wrapperGoArchiveAssets, "wrapperGoArchiveAssets");
      return this;
    }

    public Builder addMaterializationAsset(
        String classpathResource, String relativePath, boolean executable) {
      return addMaterializationAsset(
          new EmbeddedAsset(classpathResource, relativePath, executable));
    }

    public Builder addMaterializationAsset(EmbeddedAsset embeddedAsset) {
      this.floxMaterializationAssets.add(Objects.requireNonNull(embeddedAsset, "embeddedAsset"));
      return this;
    }

    public Builder clearMaterializationAssets() {
      floxMaterializationAssets.clear();
      return this;
    }

    public Builder addInstallerAsset(
        String configMapKey, String classpathResource, String mountPath) {
      return addInstallerAsset(
          InstallerAsset.builder()
              .configMapKey(configMapKey)
              .classpathResource(classpathResource)
              .mountPath(mountPath)
              .build());
    }

    public Builder addInstallerAsset(Consumer<InstallerAsset.Builder> installerAssetBuilder) {
      Objects.requireNonNull(installerAssetBuilder, "installerAssetBuilder");
      InstallerAsset.Builder builder = InstallerAsset.builder();
      installerAssetBuilder.accept(builder);
      return addInstallerAsset(builder.build());
    }

    public Builder addInstallerAsset(InstallerAsset installerAsset) {
      this.floxInstallerConfigMapAssets.add(
          Objects.requireNonNull(installerAsset, "installerAsset"));
      return this;
    }

    public Builder clearInstallerAssets() {
      floxInstallerConfigMapAssets.clear();
      return this;
    }

    public FloxContainerdShimAssets build() {
      return new FloxContainerdShimAssets(this);
    }
  }

  public static final class InstallerAsset {
    private final String configMapKey;
    private final String classpathResource;
    private final String mountPath;

    private InstallerAsset(Builder builder) {
      this.configMapKey = Objects.requireNonNull(builder.configMapKey, "configMapKey");
      this.classpathResource =
          Objects.requireNonNull(builder.classpathResource, "classpathResource");
      this.mountPath = Objects.requireNonNull(builder.mountPath, "mountPath");
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

    public String mountPath() {
      return mountPath;
    }

    public static final class Builder {
      private String configMapKey;
      private String classpathResource;
      private String mountPath;

      private Builder() {}

      public Builder configMapKey(String configMapKey) {
        this.configMapKey = configMapKey;
        return this;
      }

      public Builder classpathResource(String classpathResource) {
        this.classpathResource = classpathResource;
        return this;
      }

      public Builder mountPath(String mountPath) {
        this.mountPath = mountPath;
        return this;
      }

      public InstallerAsset build() {
        return new InstallerAsset(this);
      }
    }
  }
}
