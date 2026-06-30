package io.nxmatic.rke2lab.manifests.port;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Materialises the Flox runtime installer assets owned by the manifests world: the installer
 * scripts, per-environment {@code .flox} trees, NRI plugin sources and debug tooling the host
 * trampolines into. Assembling and laying out those assets is manifest <em>description</em>, so it
 * lives here; the host only asks for the tree to be written and reads which environments were
 * discovered.
 */
public interface FloxRuntimeAssetService {

  /** Stable provider identifier for diagnostics. */
  String providerId();

  /**
   * Write the full installer asset tree under {@code targetDir} (created if absent), laid out at
   * the relative paths the host installer and DaemonSet expect.
   */
  void writeInstallerAssetTree(Path targetDir) throws IOException;

  /**
   * The Flox environments discovered on the manifests-world classpath, sorted by category then
   * name.
   */
  List<FloxEnvironment> discoveredEnvironments();

  /**
   * A discovered Flox environment: the {@code category/name} pair (e.g. {@code networking/kdns}).
   */
  record FloxEnvironment(String category, String name) {}
}
