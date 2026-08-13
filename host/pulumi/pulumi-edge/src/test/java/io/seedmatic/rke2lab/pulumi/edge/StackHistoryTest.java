package io.seedmatic.rke2lab.pulumi.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.pulumi.edge.StackHistory.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("host")
class StackHistoryTest {

  @TempDir Path tempDir;

  @Test
  void twoPairsReturnsTwoEntriesSortedByDeploymentTime() throws Exception {
    Path historyDir = prepareHistoryDir(tempDir, "p", "sandbox");

    writeHistoryPair(historyDir, 1, 1780925429);
    writeHistoryPair(historyDir, 2, 1780925500);

    StackHistory history = StackHistory.of(tempDir, "p", "sandbox");
    List<Entry> entries = history.entries();

    assertEquals(2, entries.size());
    assertEquals(Instant.ofEpochSecond(1780925429), entries.get(0).when());
    assertEquals(Instant.ofEpochSecond(1780925500), entries.get(1).when());
    assertTrue(Files.exists(entries.get(0).file()));
    assertTrue(Files.exists(entries.get(1).file()));
  }

  @Test
  void entriesAreOrderedByDeploymentTimeNotVersion() throws Exception {
    // The real failure mode: the file backend leaves version at 0 for every deployment, so version
    // cannot order the timeline — the file name's nanosecond stamp (deployment time) must. Write
    // the
    // LATER deployment first, both with version 0, and assert the read order is chronological.
    Path historyDir = prepareHistoryDir(tempDir, "p", "sandbox");

    writeHistoryPair(historyDir, 0, 1780925500);
    writeHistoryPair(historyDir, 0, 1780925429);

    StackHistory history = StackHistory.of(tempDir, "p", "sandbox");
    List<Entry> entries = history.entries();

    assertEquals(2, entries.size());
    assertEquals(Instant.ofEpochSecond(1780925429), entries.get(0).when());
    assertEquals(Instant.ofEpochSecond(1780925500), entries.get(1).when());
  }

  @Test
  void checkpointOfReturnsSnapshotWithConsultationReport() throws Exception {
    Path historyDir = prepareHistoryDir(tempDir, "p", "sandbox");
    writeHistoryPair(historyDir, 2, 1780925500);

    StackHistory history = StackHistory.of(tempDir, "p", "sandbox");
    List<Entry> entries = history.entries();

    Entry entry = entries.get(0);
    Optional<StackCheckpoint> checkpointOpt = history.checkpointOf(entry);

    assertTrue(checkpointOpt.isPresent());
    StackSnapshot snapshot = checkpointOpt.get().snapshot();
    List<Object> reports = snapshot.outputsNamed("consultationReport");
    assertEquals(1, reports.size());
  }

  @Test
  void attrsFilesAreExcluded() throws Exception {
    Path historyDir = prepareHistoryDir(tempDir, "p", "sandbox");

    writeHistoryPair(historyDir, 1, 1780925429);
    writeHistoryPair(historyDir, 2, 1780925500);

    Files.writeString(
        historyDir.resolve("sandbox-" + (1780925429L * 1_000_000_000L) + ".history.json.attrs"),
        "ignored");

    StackHistory history = StackHistory.of(tempDir, "p", "sandbox");
    List<Entry> entries = history.entries();

    assertEquals(2, entries.size());
  }

  @Test
  void absentHistoryDirReturnsEmptyList() throws Exception {
    StackHistory history = StackHistory.of(tempDir, "p", "sandbox");
    List<Entry> entries = history.entries();

    assertTrue(entries.isEmpty());
  }

  @Test
  void malformedHistoryFileThrowsStackContentException() throws Exception {
    Path historyDir = prepareHistoryDir(tempDir, "p", "sandbox");

    Path malformedFile = historyDir.resolve("sandbox-300.history.json");
    Files.writeString(malformedFile, "{ not json");

    StackHistory history = StackHistory.of(tempDir, "p", "sandbox");

    StackContentException thrown = assertThrows(StackContentException.class, history::entries);
    assertEquals(malformedFile, thrown.path());
    assertNotNull(thrown.getCause());
  }

  @Test
  void entrySucceededIsFalseForFailedResult() throws Exception {
    Path historyDir = prepareHistoryDir(tempDir, "p", "sandbox");
    writeHistoryPair(historyDir, 1, 1780925429, "failed");

    StackHistory history = StackHistory.of(tempDir, "p", "sandbox");
    List<Entry> entries = history.entries();

    assertEquals(1, entries.size());
    assertFalse(entries.get(0).succeeded());
  }

  private Path prepareHistoryDir(Path base, String project, String stack) throws IOException {
    Path historyDir = PulumiBackendLayout.historyDir(base, project, stack);
    Files.createDirectories(historyDir);
    return historyDir;
  }

  private void writeHistoryPair(Path historyDir, int version, long startTimeSeconds)
      throws IOException {
    writeHistoryPair(historyDir, version, startTimeSeconds, "succeeded");
  }

  private void writeHistoryPair(Path historyDir, int version, long startTimeSeconds, String result)
      throws IOException {
    // The deployment instant is the file name's nanosecond stamp; encode startTimeSeconds there so
    // the parsed `when` is Instant.ofEpochSecond(startTimeSeconds).
    String stem = "sandbox-" + (startTimeSeconds * 1_000_000_000L);
    String historyJson =
        """
        {
          "version": %d,
          "startTime": %d,
          "result": "%s"
        }
        """
            .formatted(version, startTimeSeconds, result);

    String checkpointJson =
        """
        {
          "version": 3,
          "checkpoint": {
            "latest": {
              "resources": [
                {
                  "type": "x",
                  "outputs": {
                    "consultationReport": {
                      "checkpointId": "c"
                    }
                  }
                }
              ]
            }
          }
        }
        """;

    Files.writeString(historyDir.resolve(stem + ".history.json"), historyJson);
    Files.writeString(historyDir.resolve(stem + ".checkpoint.json"), checkpointJson);
  }
}
