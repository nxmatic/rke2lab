package io.nxmatic.rke2lab.controlplane.incus;

import io.nxmatic.rke2lab.manifests.port.FloxRuntimeAssetService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

  private static final String CLASSPATH_ROOT = "META-INF/io.nxmatic/rke2lab/controlplane";
  private static final String CLASSPATH_SYSTEMD_SCRIPTS_ROOT =
      CLASSPATH_ROOT + "/incus/manifests/systemd/systemd-scripts";

  private final List<Path> materializedPaths = new ArrayList<>();
  private final FloxRuntimeAssetService floxAssetService;

  SystemdTarget(FloxRuntimeAssetService floxAssetService) {
    this.floxAssetService = floxAssetService;
  }

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

    // Scripts come from classpath (static resources)
    ClasspathTreeCopier.copy(CLASSPATH_SYSTEMD_SCRIPTS_ROOT, paths.scriptsRoot(), true);

    // Units are already copied to paths.systemdRoot() by synthesizeAndExplodeManifests upstream

    materializedPaths.add(paths.scriptsRoot());
    materializedPaths.add(paths.systemdRoot());

    // Flox runtime installer assets.
    final Path floxRuntimeTarget = paths.daemonsetRoot().resolve("runtime").resolve("flox");
    if (Files.exists(floxRuntimeTarget)) {
      deleteSubtree(floxRuntimeTarget);
    }
    Files.createDirectories(floxRuntimeTarget);
    floxAssetService.writeInstallerAssetTree(floxRuntimeTarget);
    materializedPaths.add(floxRuntimeTarget);
  }

  /**
   * The flox runtime asset service, exposing the discovered flox environments for slot manifest
   * generation.
   */
  public FloxRuntimeAssetService floxAssetService() {
    return floxAssetService;
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
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
  }
}
