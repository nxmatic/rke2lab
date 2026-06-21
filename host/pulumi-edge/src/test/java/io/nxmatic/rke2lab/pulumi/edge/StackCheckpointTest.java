package io.nxmatic.rke2lab.pulumi.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("host")
class StackCheckpointTest {

  @Test
  void snapshot_loadsValidCheckpointWithConsultationReport() throws Exception {
    // Given: fixture checkpoint with one consultationReport in a Checkpoint resource
    Path fixturePath =
        Path.of(getClass().getResource("/checkpoints/sample.checkpoint.json").toURI());

    // When: load and extract snapshot
    StackSnapshot snapshot = StackCheckpoint.of(fixturePath).snapshot();

    // Then: snapshot contains exactly one consultationReport with expected structure
    List<Object> reports = snapshot.outputsNamed("consultationReport");
    assertEquals(1, reports.size(), "Should find exactly one consultationReport output");
  }

  @Test
  void snapshot_throws_whenFileDoesNotExist() throws Exception {
    // Given: path to non-existent file
    Path missingPath = Path.of("/no/such/file.json");

    // When: attempt to load snapshot
    StackAccessException ex =
        assertThrows(StackAccessException.class, () -> StackCheckpoint.of(missingPath).snapshot());

    // Then: exception carries the file path and cause is NoSuchFileException
    assertEquals(missingPath, ex.path());
    assertInstanceOf(NoSuchFileException.class, ex.getCause());
  }

  @Test
  void snapshot_throws_whenFileIsCorrupt(@TempDir Path tempDir) throws Exception {
    // Given: file with invalid JSON
    Path corruptPath = tempDir.resolve("corrupt.json");
    Files.writeString(corruptPath, "{ this is not json");

    // When: attempt to load snapshot
    StackContentException ex =
        assertThrows(StackContentException.class, () -> StackCheckpoint.of(corruptPath).snapshot());

    // Then: exception carries the file path and has a non-null cause
    assertEquals(corruptPath, ex.path());
    assertNotNull(ex.getCause());
  }

  @Test
  void snapshot_throws_whenPresentButMissingRequiredFields(@TempDir Path tempDir) throws Exception {
    // Given: valid JSON file missing checkpoint.latest field
    Path invalidPath = tempDir.resolve("invalid-structure.json");
    Files.writeString(invalidPath, "{\"version\":3,\"checkpoint\":{}}");

    // When: attempt to load snapshot
    StackContentException ex =
        assertThrows(StackContentException.class, () -> StackCheckpoint.of(invalidPath).snapshot());

    // Then: exception carries the file path and has a non-null cause
    assertEquals(invalidPath, ex.path());
    assertNotNull(ex.getCause());
  }
}
