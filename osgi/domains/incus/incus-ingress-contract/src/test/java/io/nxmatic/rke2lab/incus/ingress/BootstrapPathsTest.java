package io.nxmatic.rke2lab.incus.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BootstrapPathsTest {

  @Test
  void automountPath_bioskop_layout_with_private_segment() {
    final Path bioskopRoot = Path.of("/private/var/lib/git/nxmatic/rke2lab");
    final Path result = BootstrapPaths.automountPath(bioskopRoot, true, "/net/bioskop.local");
    assertEquals(Path.of("/net/bioskop.local/private/var/lib/git/nxmatic/rke2lab"), result);
  }

  @Test
  void automountPath_nikopol_layout_without_private_segment() {
    final Path nikopolRoot = Path.of("/Volumes/git-worktree-store/nxmatic/rke2lab");
    final Path result = BootstrapPaths.automountPath(nikopolRoot, true, "/net/nikopol.local");
    assertEquals(Path.of("/net/nikopol.local/Volumes/git-worktree-store/nxmatic/rke2lab"), result);
  }

  @Test
  void automountPath_preserves_path_when_automount_disabled() {
    final Path path = Path.of("/private/var/lib/git/nxmatic/rke2lab");
    final Path result = BootstrapPaths.automountPath(path, false, "/net/bioskop.local");
    assertEquals(path.toAbsolutePath().normalize(), result);
  }

  @Test
  void automountPath_skips_translation_for_net_paths() {
    final Path netPath = Path.of("/net/bioskop.local/private/var/lib/git/nxmatic/rke2lab");
    final Path result = BootstrapPaths.automountPath(netPath, true, "/net/other.local");
    assertEquals(netPath, result);
  }

  @Test
  void instanceMounts_yields_thirteen_mounts() {
    final BootstrapPaths paths =
        BootstrapPaths.fromLocalWorktree(Path.of("/tmp/worktree"), "test-cluster", "test-node");
    assertEquals(13, paths.instanceMounts().size());
  }

  @Test
  void instanceMounts_maintains_device_names_and_targets() {
    final BootstrapPaths paths =
        BootstrapPaths.fromLocalWorktree(Path.of("/tmp/worktree"), "test-cluster", "test-node");
    final var mounts = paths.instanceMounts();

    assertEquals("worktree.dir", mounts.get(0).deviceName());
    assertEquals(BootstrapPaths.HostPathCatalog.WORKTREE.path(), mounts.get(0).target());

    assertEquals("git.dir", mounts.get(3).deviceName());
    assertEquals(BootstrapPaths.HostPathCatalog.GIT_WORKTREE.path(), mounts.get(3).target());

    assertEquals("manifests.dir", mounts.get(6).deviceName());
    assertEquals(BootstrapPaths.HostPathCatalog.MANIFESTS.path(), mounts.get(6).target());
  }
}
