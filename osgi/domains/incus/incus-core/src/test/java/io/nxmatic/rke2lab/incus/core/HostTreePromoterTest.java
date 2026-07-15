package io.nxmatic.rke2lab.incus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the ACT of the reconcile (R1 + skip-flox): a promotion syncs a staging into the live tree,
 * deleting stale entries but preserving flox runtime state on both sides. The invariants the design
 * demands before trusting live mounts — a mount dir survives {@code setDelete(true)}, and {@code
 * .flox/} is neither copied nor deleted.
 */
class HostTreePromoterTest {

  private final HostTreePromoter promoter = new HostTreePromoter();

  @Test
  void it_syncs_content_deletes_stale_and_preserves_flox(@TempDir Path tmp) throws IOException {
    final Path source = tmp.resolve("host.1.staging.d");
    final Path live = tmp.resolve("host.live.d");

    // source: the freshly materialised staging — a manifest + a mount dir, and its own .flox state.
    write(source.resolve("rke2-manifests.d/deployment.yaml"), "image: v2");
    Files.createDirectories(source.resolve("worktree.d")); // a mount dir, empty in the staging
    write(source.resolve("env.d/.flox/run/socket"), "STAGING-flox-should-not-be-copied");

    // live: the previous promotion — an OLD manifest, a STALE file to be deleted, and LIVE flox
    // state the node manages that must survive the --delete.
    write(live.resolve("rke2-manifests.d/deployment.yaml"), "image: v1");
    write(live.resolve("rke2-manifests.d/stale.yaml"), "removed in v2");
    write(live.resolve("env.d/.flox/run/socket"), "LIVE-flox-must-survive");

    promoter.promote(source, live);

    // content synced: the manifest updated to the staging's version.
    assertEquals("image: v2", read(live.resolve("rke2-manifests.d/deployment.yaml")));
    // stale entry deleted (--delete behaviour).
    assertFalse(
        Files.exists(live.resolve("rke2-manifests.d/stale.yaml")),
        "a file absent from the staging is deleted from the live");
    // the mount dir present in the staging survives (R1).
    assertTrue(Files.isDirectory(live.resolve("worktree.d")), "a mount dir survives the sync");
    // skip-flox BOTH ways: the live's flox state was NOT overwritten by the staging's, NOT deleted.
    assertEquals(
        "LIVE-flox-must-survive",
        read(live.resolve("env.d/.flox/run/socket")),
        "live flox runtime state is neither overwritten nor deleted");
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file);
  }
}
