package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.pulumi.automation.StackAccessException;
import io.nxmatic.rke2lab.pulumi.automation.StackHandle;
import io.nxmatic.rke2lab.pulumi.automation.StackHistory;
import io.nxmatic.rke2lab.pulumi.automation.StackSnapshot;
import io.nxmatic.rke2lab.pulumi.automation.testkit.StackHistoryFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the pure-delegate contract of {@link StackHandleSnapshotSource}: it forwards to its {@link
 * StackHandle} and never degrades a read failure to empty. The only nothing-here is a genuinely
 * absent history (empty timeline / empty latest); a present-but-unreadable checkpoint propagates as
 * {@link StackAccessException}.
 */
class StackHandleSnapshotSourceTest {

  private static final String PROJECT = "rke2lab";
  private static final String STACK = "dev";

  private static StackHandleSnapshotSource sourceOver(StackHistoryFixture fixture) {
    return new StackHandleSnapshotSource(
        StackHandle.forBackend(fixture.backendDir(), PROJECT, STACK));
  }

  @Test
  void timeline_twoUpdates_yieldsBothEntriesInDeploymentTimeOrder(@TempDir Path tempDir)
      throws Exception {
    final StackHistoryFixture fixture =
        StackHistoryFixture.at(tempDir, PROJECT, STACK)
            .update(1, 1_000L, "systemd-adapter")
            .update(2, 2_000L, "cluster-readiness");

    final List<StackHistory.Entry> timeline = sourceOver(fixture).timeline();

    assertEquals(2, timeline.size());
    assertEquals(
        List.of(Instant.ofEpochSecond(1_000L), Instant.ofEpochSecond(2_000L)),
        timeline.stream().map(StackHistory.Entry::when).toList());
  }

  @Test
  void at_latestEntry_materializesSnapshotCarryingItsConsultationReport(@TempDir Path tempDir)
      throws Exception {
    final StackHistoryFixture fixture =
        StackHistoryFixture.at(tempDir, PROJECT, STACK).update(1, 1_000L, "systemd-adapter");
    final StackHandleSnapshotSource source = sourceOver(fixture);

    final StackHistory.Entry latest = source.timeline().get(0);
    final StackSnapshot snapshot = source.at(latest);

    assertEquals(1, snapshot.outputsNamed(ConsultationReport.OUTPUT_KEY).size());
  }

  @Test
  void latest_historyPresent_isThePresentSnapshotWithItsReport(@TempDir Path tempDir)
      throws Exception {
    final StackHistoryFixture fixture =
        StackHistoryFixture.at(tempDir, PROJECT, STACK).update(1, 1_000L, "systemd-adapter");

    final Optional<StackSnapshot> latest = sourceOver(fixture).latest();

    assertTrue(latest.isPresent());
    assertEquals(1, latest.get().outputsNamed(ConsultationReport.OUTPUT_KEY).size());
  }

  @Test
  void noHistory_latestIsEmptyAndTimelineIsEmpty_genuineNothingHere(@TempDir Path tempDir)
      throws Exception {
    // A fresh backend with no fixture updates: absence, not failure.
    final StackHandleSnapshotSource source =
        new StackHandleSnapshotSource(StackHandle.forBackend(tempDir, PROJECT, STACK));

    assertTrue(source.latest().isEmpty());
    assertTrue(source.timeline().isEmpty());
  }

  @Test
  void at_checkpointDeletedAfterListing_propagatesStackAccessException(@TempDir Path tempDir)
      throws Exception {
    final StackHistoryFixture fixture =
        StackHistoryFixture.at(tempDir, PROJECT, STACK).update(1, 1_000L, "systemd-adapter");
    final StackHandleSnapshotSource source = sourceOver(fixture);

    // The history.json still lists the entry, but its checkpoint is gone — a present entry that
    // cannot be read. The delegate must re-throw, never degrade to empty.
    final StackHistory.Entry entry = source.timeline().get(0);
    Files.delete(fixture.lastCheckpointFile());

    assertThrows(StackAccessException.class, () -> source.at(entry));
  }
}
