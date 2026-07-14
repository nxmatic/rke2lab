package io.nxmatic.rke2lab.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@code host.<N>.staging.d} rotation: the filesystem is the state, the next slot is
 * {@code (max present + 1) mod 3} — an empty node yields 0, a partial set fills the gap upward, and
 * a full set wraps to overwrite the oldest position. Plus the staging view rebases every root under
 * the chosen slot.
 */
class HostSlotSelectorTest {

  private static Path stagingDir(Path nodeRoot, int slot) {
    return nodeRoot.resolve("host." + slot + ".staging.d");
  }

  private static void mkSlots(Path nodeRoot, int... slots) throws IOException {
    for (int slot : slots) {
      Files.createDirectories(stagingDir(nodeRoot, slot));
    }
  }

  @Test
  void emptyNode_yieldsSlotZero(@TempDir Path nodeRoot) {
    assertEquals(
        stagingDir(nodeRoot, 0),
        new HostSlotSelector(nodeRoot).nextStaging(),
        "a fresh node materialises into slot 0");
  }

  @Test
  void nonExistentNode_yieldsSlotZero(@TempDir Path parent) {
    final Path nodeRoot = parent.resolve("never-created");
    assertEquals(
        stagingDir(nodeRoot, 0),
        new HostSlotSelector(nodeRoot).nextStaging(),
        "an absent node dir is a legitimate empty rotation → slot 0");
  }

  @Test
  void slotsZeroAndOnePresent_yieldsSlotTwo(@TempDir Path nodeRoot) throws IOException {
    mkSlots(nodeRoot, 0, 1);
    assertEquals(
        stagingDir(nodeRoot, 2),
        new HostSlotSelector(nodeRoot).nextStaging(),
        "with {0,1} present the next is 2");
  }

  @Test
  void fullRotation_wrapsToZero(@TempDir Path nodeRoot) throws IOException {
    mkSlots(nodeRoot, 0, 1, 2);
    assertEquals(
        stagingDir(nodeRoot, 0),
        new HostSlotSelector(nodeRoot).nextStaging(),
        "with {0,1,2} present the rotation wraps to overwrite the oldest position (0)");
  }

  @Test
  void ignoresNonStagingSiblings(@TempDir Path nodeRoot) throws IOException {
    mkSlots(nodeRoot, 0);
    Files.createDirectories(nodeRoot.resolve("host.live.d"));
    Files.writeString(nodeRoot.resolve("host.0.drift.diff"), "");
    Files.createDirectories(nodeRoot.resolve("cloud.d"));
    assertEquals(
        stagingDir(nodeRoot, 1),
        new HostSlotSelector(nodeRoot).nextStaging(),
        "only host.<N>.staging.d counts — live, delta files, and unrelated dirs are ignored");
  }

  @Test
  void pinnedSlot_isSkipped_whenTheRotationWouldLandOnIt(@TempDir Path nodeRoot)
      throws IOException {
    // {0,1} present → naive next is 2; but if the live mirrors slot 2, materialising there would
    // overwrite the deltas' pivot tree. The selector steps forward to the next free position (0).
    mkSlots(nodeRoot, 0, 1);
    final Path pinned = stagingDir(nodeRoot, 2);
    assertEquals(
        stagingDir(nodeRoot, 0),
        new HostSlotSelector(nodeRoot).nextStaging(pinned),
        "the pinned live.syncedFrom slot is never overwritten — the rotation steps past it");
  }

  @Test
  void pinnedSlot_isIgnored_whenTheRotationDoesNotLandOnIt(@TempDir Path nodeRoot)
      throws IOException {
    // {0,1} present → naive next is 2; the live mirrors slot 0, which the rotation would not touch
    // anyway, so pinning changes nothing.
    mkSlots(nodeRoot, 0, 1);
    final Path pinned = stagingDir(nodeRoot, 0);
    assertEquals(
        stagingDir(nodeRoot, 2),
        new HostSlotSelector(nodeRoot).nextStaging(pinned),
        "pinning a slot the rotation avoids anyway leaves the choice unchanged");
  }

  @Test
  void stagingView_rebasesEveryRootUnderTheSlot(@TempDir Path worktree) {
    final BootstrapPaths base = BootstrapPaths.fromLocalWorktree(worktree, "bioskop", "master");
    final Path slot = base.clusterNodeRoot().resolve("host.0.staging.d");

    final BootstrapPaths staged = base.asStagingView(slot);

    assertEquals(slot, staged.assetsRoot(), "the assets root becomes the slot");
    assertTrue(
        staged.manifestsRoot().startsWith(slot), "the manifests tree materialises under the slot");
    assertTrue(
        staged.systemdRoot().startsWith(slot), "the systemd tree materialises under the slot");
    assertTrue(
        staged.runtimeCloudConfigRoot().startsWith(slot),
        "the cloud-config tree materialises under the slot");
  }
}
