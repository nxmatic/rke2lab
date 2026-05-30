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
  private static final String CLASSPATH_SYSTEMD_SCRIPTS_ROOT =
      CLASSPATH_ROOT + "/incus/manifests/systemd/systemd-scripts";
  private static final String CLASSPATH_SYSTEMD_UNITS_ROOT =
      CLASSPATH_ROOT + "/incus/manifests/systemd/systemd-units";

  private final List<Path> materializedPaths = new ArrayList<>();
  private FloxRuntimeAssets floxRuntimeAssets;

  @Override
  public String name() {
    return "systemd";
  }

  @Override
  public TargetReloadPolicy reloadPolicy() {
    return TargetReloadPolicy.DYNAMIC;
  }

  @Override
  public void materialize(IncusResourceBootstrap.BootstrapPaths paths) throws IOException {
    materializedPaths.clear();

    // Systemd units and scripts (systemd loads these at runtime). Materialize directly into the
    // configured staging paths — they live at <assetsRoot>/systemd.d/, not under manifestsRoot.
    ClasspathTreeCopier.copy(CLASSPATH_SYSTEMD_SCRIPTS_ROOT, paths.scriptsRoot(), true);
    ClasspathTreeCopier.copy(CLASSPATH_SYSTEMD_UNITS_ROOT, paths.systemdRoot(), false);
    materializedPaths.add(paths.scriptsRoot());
    materializedPaths.add(paths.systemdRoot());

    // Flox runtime installer assets.
    final Path floxRuntimeTarget = paths.daemonsetRoot().resolve("runtime").resolve("flox");
    if (Files.exists(floxRuntimeTarget)) {
      deleteSubtree(floxRuntimeTarget);
    }
    Files.createDirectories(floxRuntimeTarget);
    this.floxRuntimeAssets = FloxRuntimeAssets.builder().build();
    this.floxRuntimeAssets.writeInstallerAssetTree(floxRuntimeTarget);
    materializedPaths.add(floxRuntimeTarget);
  }

  /**
   * Returns the FloxRuntimeAssets instance used during materialization.
   *
   * <p>Provides access to discovered flox environments for slot manifest generation.
   *
   * @return the flox runtime assets, or {@code null} if not yet materialized
   */
  public FloxRuntimeAssets getFloxRuntimeAssets() {
    return floxRuntimeAssets;
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
