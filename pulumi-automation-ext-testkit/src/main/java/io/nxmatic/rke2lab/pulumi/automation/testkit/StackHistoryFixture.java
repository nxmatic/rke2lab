package io.nxmatic.rke2lab.pulumi.automation.testkit;

import io.nxmatic.rke2lab.pulumi.automation.PulumiBackendLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the file-backend on-disk layout a Pulumi stack leaves behind, so tests across modules read
 * a real history/checkpoint tree instead of hand-rolling one each. Shared from a main artifact (not
 * a test-jar) because m2e does not resolve test-jar classpaths; consumers depend on it in test
 * scope.
 *
 * <p>Each added update writes the sibling pair under {@code
 * <backendDir>/.pulumi/history/<project>/<stack>/}: {@code <stem>.history.json} (the update summary
 * — version, startTime, result) and {@code <stem>.checkpoint.json} (the state, carrying one
 * resource with a {@code consultationReport} output). It returns only paths ({@link #backendDir()},
 * {@link #historyDir()}, {@link #lastCheckpointFile()}); a consumer builds its own {@code
 * StackHandle.forBackend(backendDir, project, stack)} — the fixture stays free of the classes under
 * test so the reactor does not become cyclic.
 */
public final class StackHistoryFixture {

  private final Path backendDir;
  private final String project;
  private final String stack;
  private final Path historyDir;
  private final List<String> stems = new ArrayList<>();

  private StackHistoryFixture(Path backendDir, String project, String stack) {
    this.backendDir = backendDir;
    this.project = project;
    this.stack = stack;
    this.historyDir = PulumiBackendLayout.historyDir(backendDir, project, stack);
    try {
      Files.createDirectories(historyDir);
    } catch (IOException e) {
      throw new UncheckedIOException("could not create history dir " + historyDir, e);
    }
  }

  /** A fixture rooted at {@code backendDir} for {@code project}/{@code stack}. */
  public static StackHistoryFixture at(Path backendDir, String project, String stack) {
    return new StackHistoryFixture(backendDir, project, stack);
  }

  /**
   * Writes one update: the {@code .history.json}/{@code .checkpoint.json} pair named {@code
   * <stack>-<startTimeEpochSeconds * 1e9>} (the deployment-instant nanosecond stamp the real
   * backend uses), with {@code result == "succeeded"} and a checkpoint whose single resource
   * carries the given {@code consultationReport} output value.
   */
  public StackHistoryFixture update(int version, long startTimeEpochSeconds, String checkpointId) {
    // Encode the deployment instant as the file name's nanosecond stamp (as the real backend does),
    // so StackHistory derives when == Instant.ofEpochSecond(startTimeEpochSeconds): the file name
    // is
    // the timeline's ordering key, not the JSON version (which the file backend leaves at 0).
    final String stem = stack + "-" + (startTimeEpochSeconds * 1_000_000_000L);
    final String historyJson =
        """
        {
          "version": %d,
          "startTime": %d,
          "result": "succeeded"
        }
        """
            .formatted(version, startTimeEpochSeconds);
    final String checkpointJson =
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
                      "checkpointId": "%s"
                    }
                  }
                }
              ]
            }
          }
        }
        """
            .formatted(checkpointId);
    write(stem + ".history.json", historyJson);
    write(stem + ".checkpoint.json", checkpointJson);
    stems.add(stem);
    return this;
  }

  /**
   * Writes one update whose checkpoint carries a caller-supplied {@code latest} body — the {@code
   * {"resources":[...]}} object that goes under {@code checkpoint.latest}. Lets a consumer that
   * owns the real report shape (or a real lifted dev checkpoint) inject arbitrary resource/outputs
   * JSON without this fixture depending on those types. Named by the same nanosecond stamp
   * convention so the deployment instant is {@code Instant.ofEpochSecond(startTimeEpochSeconds)}.
   */
  public StackHistoryFixture updateWithLatest(long startTimeEpochSeconds, String latestJson) {
    final String stem = stack + "-" + (startTimeEpochSeconds * 1_000_000_000L);
    final String historyJson =
        """
        {
          "version": 0,
          "startTime": %d,
          "result": "succeeded"
        }
        """
            .formatted(startTimeEpochSeconds);
    final String checkpointJson =
        """
        {
          "version": 3,
          "checkpoint": {
            "latest": %s
          }
        }
        """
            .formatted(latestJson);
    write(stem + ".history.json", historyJson);
    write(stem + ".checkpoint.json", checkpointJson);
    stems.add(stem);
    return this;
  }

  /** The backend root — feed it to {@code StackHandle.forBackend(backendDir, project, stack)}. */
  public Path backendDir() {
    return backendDir;
  }

  /** The history directory holding the written pairs. */
  public Path historyDir() {
    return historyDir;
  }

  /** The checkpoint file path for the most recently written update. */
  public Path lastCheckpointFile() {
    if (stems.isEmpty()) {
      throw new IllegalStateException("no update written yet");
    }
    return historyDir.resolve(stems.get(stems.size() - 1) + ".checkpoint.json");
  }

  private void write(String fileName, String content) {
    try {
      Files.writeString(historyDir.resolve(fileName), content);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write fixture file " + fileName, e);
    }
  }
}
