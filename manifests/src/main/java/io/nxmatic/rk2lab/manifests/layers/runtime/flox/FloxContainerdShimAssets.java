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

public final class FloxContainerdShimAssets {

  private static final String FLOX_RESOURCE_ROOT = "/runtime/flox-containerd-shim";

  private static final List<EmbeddedAsset> FLOX_MATERIALIZATION_ASSETS =
      List.of(
          asset(FLOX_RESOURCE_ROOT + "/shim-build.sh", "shim-build.sh", true),
          asset(FLOX_RESOURCE_ROOT + "/shim-build.yaml", "shim-build.yaml", false),
          asset(FLOX_RESOURCE_ROOT + "/flake.nix", "flake.nix", false),
          asset(FLOX_RESOURCE_ROOT + "/shim-installer.sh", "shim-installer.sh", true),
          asset(FLOX_RESOURCE_ROOT + "/flox-rootfs-sync.sh", "flox-rootfs-sync.sh", true),
          asset(
              FLOX_RESOURCE_ROOT + "/debug-tools/.sh.d/rke2lab-debug-tooling.sh",
              "debug-tools/.sh.d/rke2lab-debug-tooling.sh",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/debug-tools/attach_live_flox_shim_strace.sh",
              "debug-tools/attach_live_flox_shim_strace.sh",
              true),
          asset(
              FLOX_RESOURCE_ROOT + "/debug-tools/crictl-kdns-repro.sh",
              "debug-tools/crictl-kdns-repro.sh",
              true),
          asset(
              FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-bundle-watch.sh",
              "debug-tools/kdns-containerd-bundle-watch.sh",
              true),
          asset(
              FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-remote-capture.sh",
              "debug-tools/kdns-containerd-remote-capture.sh",
              true),
          asset(
              FLOX_RESOURCE_ROOT + "/debug-tools/master-shim-pprof.sh",
              "debug-tools/master-shim-pprof.sh",
              true),
          asset(
              FLOX_RESOURCE_ROOT + "/mesh/headplane/flake.nix", "mesh/headplane/flake.nix", false),
          asset(
              FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/.gitattributes",
              "mesh/headplane/.flox/.gitattributes",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/.gitignore",
              "mesh/headplane/.flox/.gitignore",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env.json",
              "mesh/headplane/.flox/env.json",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env.lock",
              "mesh/headplane/.flox/env.lock",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env/manifest.toml",
              "mesh/headplane/.flox/env/manifest.toml",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/mesh/headplane/.flox/env/manifest.lock",
              "mesh/headplane/.flox/env/manifest.lock",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/networking/kdns/flake.nix",
              "networking/kdns/flake.nix",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/.gitattributes",
              "networking/kdns/.flox/.gitattributes",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/.gitignore",
              "networking/kdns/.flox/.gitignore",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env.json",
              "networking/kdns/.flox/env.json",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env.lock",
              "networking/kdns/.flox/env.lock",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env/manifest.toml",
              "networking/kdns/.flox/env/manifest.toml",
              false),
          asset(
              FLOX_RESOURCE_ROOT + "/networking/kdns/.flox/env/manifest.lock",
              "networking/kdns/.flox/env/manifest.lock",
              false));

  private static final List<InstallerAsset> FLOX_INSTALLER_CONFIGMAP_ASSETS =
      List.of(
          installerAsset(
              "shim-installer.sh", FLOX_RESOURCE_ROOT + "/shim-installer.sh", "shim-installer.sh"),
          installerAsset(
              "shim-installer-entrypoint.sh",
              FLOX_RESOURCE_ROOT + "/shim-installer-entrypoint.sh",
              "shim-installer-entrypoint.sh"),
          installerAsset(
              "shim-build.sh", FLOX_RESOURCE_ROOT + "/shim-build.sh", "build-assets/shim-build.sh"),
          installerAsset(
              "shim-build.yaml",
              FLOX_RESOURCE_ROOT + "/shim-build.yaml",
              "build-assets/shim-build.yaml"),
          installerAsset(
              "runtime-flake.nix", FLOX_RESOURCE_ROOT + "/flake.nix", "build-assets/flake.nix"),
          installerAsset(
              "flox-rootfs-sync.sh",
              FLOX_RESOURCE_ROOT + "/flox-rootfs-sync.sh",
              "build-assets/flox-rootfs-sync.sh"),
          installerAsset(
              "debug-tools-rke2lab-debug-tooling.sh",
              FLOX_RESOURCE_ROOT + "/debug-tools/.sh.d/rke2lab-debug-tooling.sh",
              "build-assets/debug-tools/.sh.d/rke2lab-debug-tooling.sh"),
          installerAsset(
              "debug-tools-attach-live-flox-shim-strace.sh",
              FLOX_RESOURCE_ROOT + "/debug-tools/attach_live_flox_shim_strace.sh",
              "build-assets/debug-tools/attach_live_flox_shim_strace.sh"),
          installerAsset(
              "debug-tools-crictl-kdns-repro.sh",
              FLOX_RESOURCE_ROOT + "/debug-tools/crictl-kdns-repro.sh",
              "build-assets/debug-tools/crictl-kdns-repro.sh"),
          installerAsset(
              "debug-tools-kdns-containerd-bundle-watch.sh",
              FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-bundle-watch.sh",
              "build-assets/debug-tools/kdns-containerd-bundle-watch.sh"),
          installerAsset(
              "debug-tools-kdns-containerd-remote-capture.sh",
              FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-remote-capture.sh",
              "build-assets/debug-tools/kdns-containerd-remote-capture.sh"),
          installerAsset(
              "debug-tools-master-shim-pprof.sh",
              FLOX_RESOURCE_ROOT + "/debug-tools/master-shim-pprof.sh",
              "build-assets/debug-tools/master-shim-pprof.sh"),
          installerAsset(
              "mesh-headplane-flake.nix",
              FLOX_RESOURCE_ROOT + "/mesh/headplane/flake.nix",
              "build-assets/mesh/headplane/flake.nix"),
          installerAsset(
              "networking-kdns-flake.nix",
              FLOX_RESOURCE_ROOT + "/networking/kdns/flake.nix",
              "build-assets/networking/kdns/flake.nix"));

  private FloxContainerdShimAssets() {}

  public static List<EmbeddedAsset> materializationAssets() {
    final ArrayList<EmbeddedAsset> assets = new ArrayList<>(FLOX_MATERIALIZATION_ASSETS);
    assets.addAll(RuntimeDaemonsetScriptPolicyAssets.materializationAssets());
    return List.copyOf(assets);
  }

  public static Path worktreeShimAssetsRelativePath() {
    return Path.of("manifests", "src", "main", "resources", "runtime", "flox-containerd-shim");
  }

  public static Map<String, String> installerConfigMapData() {
    final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    for (InstallerAsset asset : FLOX_INSTALLER_CONFIGMAP_ASSETS) {
      data.put(asset.configMapKey(), readResource(asset.classpathResource()));
    }
    data.putAll(RuntimeDaemonsetScriptPolicyAssets.configMapData());
    data.put(WrapperGoArchiveAssets.ARCHIVE_CONFIGMAP_KEY, WrapperGoArchiveAssets.archiveBase64());
    data.put(WrapperGoArchiveAssets.MANIFEST_CONFIGMAP_KEY, WrapperGoArchiveAssets.manifestJson());
    return Map.copyOf(data);
  }

  public static Object[] installerVolumeItems() {
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
        FLOX_INSTALLER_CONFIGMAP_ASSETS.stream()
            .map(asset -> Map.of("key", asset.configMapKey(), "path", asset.mountPath()))
            .toList();

    final List<Map<String, String>> daemonsetItems =
        RuntimeDaemonsetScriptPolicyAssets.relativePathsByKey().entrySet().stream()
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

  public static void materializeSupplementaryAssetsTo(Path outputDir) {
    try {
      WrapperGoArchiveAssets.materializeTo(outputDir);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed to materialize Flox supplementary assets to " + outputDir, ex);
    }
  }

  private static EmbeddedAsset asset(
      String classpathResource, String relativePath, boolean executable) {
    return new EmbeddedAsset(classpathResource, relativePath, executable);
  }

  private static InstallerAsset installerAsset(
      String configMapKey, String classpathResource, String mountPath) {
    return new InstallerAsset(configMapKey, classpathResource, mountPath);
  }

  private static String installerBuildAssetPath(String relativePath) {
    return "build-assets/" + relativePath;
  }

  private static String readResource(String resourcePath) {
    try (InputStream input = FloxContainerdShimAssets.class.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Missing flox-containerd-shim resource: " + resourcePath);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed reading flox-containerd-shim resource: " + resourcePath, ex);
    }
  }

  private record InstallerAsset(String configMapKey, String classpathResource, String mountPath) {}
}
