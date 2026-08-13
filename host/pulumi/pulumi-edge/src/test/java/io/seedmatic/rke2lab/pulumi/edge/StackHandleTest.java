package io.seedmatic.rke2lab.pulumi.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.pulumi.edge.StackHistory.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("host")
class StackHandleTest {

  @TempDir Path tempDir;

  // Module-local fixture: this module must NOT depend on pulumi-edge-testkit (that fixture depends
  // back on this module for PulumiBackendLayout, which would cycle the reactor). The shared testkit
  // serves cross-module consumers; here we write the pairs locally, still resolving the layout
  // through PulumiBackendLayout so the path convention has a single owner.
  private Path writeTwoUpdates() throws IOException {
    writeHistoryPair(1_780_925_429L);
    writeHistoryPair(1_780_925_500L);
    return tempDir;
  }

  @Test
  void forBackendThenHistoryHasTwoEntries() throws Exception {
    StackHandle handle = StackHandle.forBackend(writeTwoUpdates(), "p", "sandbox");

    assertEquals(2, handle.history().entries().size());
  }

  @Test
  void snapshotOfReturnsSnapshotWithConsultationReport() throws Exception {
    StackHandle handle = StackHandle.forBackend(writeTwoUpdates(), "p", "sandbox");
    Entry v2Entry = handle.history().entries().get(1);

    StackSnapshot snapshot = handle.snapshotOf(v2Entry);

    assertEquals(1, snapshot.outputsNamed("consultationReport").size());
  }

  @Test
  void currentSnapshotReturnsLatestVersionSnapshot() throws Exception {
    StackHandle handle = StackHandle.forBackend(writeTwoUpdates(), "p", "sandbox");

    Optional<StackSnapshot> currentOpt = handle.currentSnapshot();

    assertTrue(currentOpt.isPresent());
    assertEquals(1, currentOpt.get().outputsNamed("consultationReport").size());
  }

  @Test
  void currentSnapshotReturnsEmptyWhenNoHistory() throws Exception {
    StackHandle handle = StackHandle.forBackend(tempDir, "p", "sandbox");

    assertTrue(handle.currentSnapshot().isEmpty());
  }

  @Test
  void snapshotOfThrowsStackAccessExceptionWhenCheckpointMissing() throws Exception {
    writeHistoryPair(1_780_925_429L);
    StackHandle handle = StackHandle.forBackend(tempDir, "p", "sandbox");
    Entry entry = handle.history().entries().get(0);

    Path checkpointFile = entry.file();
    Files.delete(checkpointFile);

    StackAccessException thrown =
        assertThrows(StackAccessException.class, () -> handle.snapshotOf(entry));
    assertEquals(checkpointFile, thrown.path());
  }

  @Test
  void attachReadsHistoryFromBackend() throws Exception {
    // exportStack() needs a live CLI (Task 14); history is backend-driven and works in either mode,
    // so this locks the attach factory + ctor wiring without the CLI.
    StackHandle handle = StackHandle.attach("sandbox", tempDir, writeTwoUpdates(), "p");

    assertEquals(2, handle.history().entries().size());
  }

  /** Writes one history+checkpoint pair, named by the deployment-instant nanosecond stamp. */
  private void writeHistoryPair(long startTimeSeconds) throws IOException {
    Path historyDir = PulumiBackendLayout.historyDir(tempDir, "p", "sandbox");
    Files.createDirectories(historyDir);
    String stem = "sandbox-" + (startTimeSeconds * 1_000_000_000L);
    Files.writeString(
        historyDir.resolve(stem + ".history.json"),
        "{\"version\":0,\"startTime\":%d,\"result\":\"succeeded\"}".formatted(startTimeSeconds));
    Files.writeString(
        historyDir.resolve(stem + ".checkpoint.json"),
        "{\"version\":3,\"checkpoint\":{\"latest\":{\"resources\":[{\"type\":\"x\","
            + "\"outputs\":{\"consultationReport\":{\"checkpointId\":\"c\"}}}]}}}");
  }
}
