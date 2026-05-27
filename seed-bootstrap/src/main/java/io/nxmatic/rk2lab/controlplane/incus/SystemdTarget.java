package io.nxmatic.rk2lab.controlplane.incus;

import io.nxmatic.rk2lab.manifests.layers.runtime.flox.FloxRuntimeAssets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Systemd target — host-side units, scripts, libexec helpers, plus the daemonset assets the host
 * trampolines into.
 *
 * <p>Materializes:
 *
 * <ul>
 *   <li>Systemd units and scripts (loaded by the host systemd daemon)
 *   <li>Flox runtime installer assets (read by DaemonSet init container, then trampolined to host)
 * </ul>
 *
 * <p>Reload policy: {@link TargetReloadPolicy#DYNAMIC}. systemd picks up unit changes via {@code
 * daemon-reload}; the DaemonSet init container + reconciler sidecar handle the flox/NRI assets via
 * the host-trampoline path.
 */
public final class SystemdTarget implements ProvisioningTarget {

  private static final String CLASSPATH_ROOT = "META-INF/io.nxmatic/rk2lab/controlplane";
  private static final String CLASSPATH_HOST_SYSTEMD_SCRIPTS_ROOT =
      CLASSPATH_ROOT + "/incus/manifests/manifests.d/host/systemd-scripts";
  private static final String CLASSPATH_HOST_SYSTEMD_UNITS_ROOT =
      CLASSPATH_ROOT + "/incus/manifests/manifests.d/host/systemd-units";

  private final List<Path> materializedPaths = new ArrayList<>();

  @Override
  public String name() {
    // On-the-wire target name kept as "node" for now to avoid a one-time checksum-key rotation in
    // outputs/ConfigMaps. Source-side type is SystemdTarget; the wire name can rotate later when
    // its checksum naturally changes.
    return "node";
  }

  @Override
  public TargetReloadPolicy reloadPolicy() {
    return TargetReloadPolicy.DYNAMIC;
  }

  @Override
  public void materialize(IncusResourceBootstrap.BootstrapPaths paths) throws IOException {
    materializedPaths.clear();

    // Systemd units and scripts (systemd loads these at runtime).
    final Path hostManifestsRoot = paths.manifestsRoot().resolve("host");
    materializeHostSystemdAssets(hostManifestsRoot);
    materializedPaths.add(hostManifestsRoot.resolve("systemd-scripts"));
    materializedPaths.add(hostManifestsRoot.resolve("systemd-units"));

    // Flox runtime installer assets.
    final Path floxRuntimeTarget = paths.daemonsetRoot().resolve("runtime").resolve("flox");

    if (Files.exists(floxRuntimeTarget)) {
      deleteSubtree(floxRuntimeTarget);
    }
    Files.createDirectories(floxRuntimeTarget);

    FloxRuntimeAssets.builder().build().writeInstallerAssetTree(floxRuntimeTarget);

    materializedPaths.add(floxRuntimeTarget);
  }

  private void materializeHostSystemdAssets(Path hostRoot) throws IOException {
    materializeResourceTree(
        CLASSPATH_HOST_SYSTEMD_SCRIPTS_ROOT, hostRoot.resolve("systemd-scripts"), true);
    materializeResourceTree(
        CLASSPATH_HOST_SYSTEMD_UNITS_ROOT, hostRoot.resolve("systemd-units"), false);
  }

  private void materializeResourceTree(
      String classpathRoot, Path targetDir, boolean executableFiles) throws IOException {
    if (!Files.exists(targetDir)) {
      Files.createDirectories(targetDir);
    }

    try (var resourceStream =
        getClass().getClassLoader().getResourceAsStream(classpathRoot + "/")) {
      if (resourceStream == null) {
        throw new IllegalStateException("Classpath root not found: " + classpathRoot);
      }
    }

    final java.nio.file.FileSystem fs;
    final Path classpathPath;
    try {
      final java.net.URI uri = getClass().getClassLoader().getResource(classpathRoot).toURI();
      if (uri.getScheme().equals("jar")) {
        fs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Collections.emptyMap());
        classpathPath = fs.getPath(classpathRoot);
      } else {
        fs = null;
        classpathPath = Path.of(uri);
      }
    } catch (Exception ex) {
      throw new IOException("Failed to open classpath root: " + classpathRoot, ex);
    }

    try {
      Files.walk(classpathPath)
          .filter(Files::isRegularFile)
          .forEach(
              source -> {
                final Path relative = classpathPath.relativize(source);
                final Path target = targetDir.resolve(relative.toString());
                try {
                  Files.createDirectories(target.getParent());
                  Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                  if (executableFiles) {
                    target.toFile().setExecutable(true);
                  }
                } catch (IOException ex) {
                  throw new java.io.UncheckedIOException(ex);
                }
              });
    } finally {
      if (fs != null) {
        fs.close();
      }
    }
  }

  @Override
  public List<Path> getMaterializedPaths() {
    return List.copyOf(materializedPaths);
  }

  private static void deleteSubtree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
    }
  }
}
