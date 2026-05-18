package io.nxmatic.rk2lab.systemdadapter.service;

import io.nxmatic.rk2lab.systemdadapter.SystemdAdapterProperties;
import io.nxmatic.rk2lab.systemdadapter.api.SystemdStatusSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ShellSystemdStatusSnapshotProvider implements SystemdStatusSnapshotProvider {

  private final SystemdAdapterProperties properties;

  public ShellSystemdStatusSnapshotProvider(SystemdAdapterProperties properties) {
    this.properties = properties;
  }

  @Override
  public SystemdStatusSnapshot currentSnapshot() {
    final String mandatoryTarget = properties.mandatoryTarget();
    final String targetState =
        normalizeState(
            runCommand("systemctl", "show", "--property=ActiveState", "--value", mandatoryTarget)
                .trim());
    final boolean mandatoryHealthy = "active".equals(targetState);

    final String jobsOutput = runCommand("systemctl", "list-jobs", "--no-legend", "--no-pager");
    final int pendingJobs = countPendingJobs(jobsOutput);
    final Map<String, Integer> jobsByState = jobsByState(jobsOutput);

    final String failedOutput = runCommand("systemctl", "--failed", "--no-legend", "--no-pager");
    final int failedUnits = countFailedUnits(failedOutput);

    final boolean runtimeReady = mandatoryHealthy && pendingJobs == 0 && failedUnits == 0;

    final String summary =
        "mandatoryTarget="
            + mandatoryTarget
            + "(state="
            + targetState
            + "), pendingJobs="
            + pendingJobs
            + ", failedUnits="
            + failedUnits;

    return new SystemdStatusSnapshot(
        Instant.now(),
        mandatoryTarget,
        targetState,
        mandatoryHealthy,
        pendingJobs,
        jobsByState,
        failedUnits,
        runtimeReady,
        summary);
  }

  private String runCommand(String... command) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    try {
      final Process process = processBuilder.start();
      final boolean completed =
          process.waitFor(properties.commandTimeoutSeconds(), TimeUnit.SECONDS);
      if (!completed) {
        process.destroyForcibly();
        throw new IllegalStateException(
            "Command timeout after "
                + Duration.ofSeconds(properties.commandTimeoutSeconds())
                + ": "
                + String.join(" ", command));
      }
      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "Command failed ("
                + process.exitValue()
                + "): "
                + String.join(" ", command)
                + " :: "
                + output);
      }
      return output;
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to execute command: " + String.join(" ", command), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for command", ex);
    }
  }

  private int countPendingJobs(String output) {
    if (output == null || output.isBlank()) {
      return 0;
    }
    int count = 0;
    for (String line : output.lines().toList()) {
      final String normalized = line == null ? "" : line.trim();
      if (normalized.isBlank()) {
        continue;
      }
      if (startsWithNumericToken(normalized)) {
        count++;
      }
    }
    return count;
  }

  private Map<String, Integer> jobsByState(String output) {
    final LinkedHashMap<String, Integer> states = new LinkedHashMap<>();
    if (output == null || output.isBlank()) {
      return states;
    }
    for (String line : output.lines().toList()) {
      final String normalized = line == null ? "" : line.trim();
      if (normalized.isBlank() || !startsWithNumericToken(normalized)) {
        continue;
      }
      final String[] columns = normalized.split("\\s+");
      final String state = columns.length >= 4 ? columns[3] : "unknown";
      states.merge(state, 1, Integer::sum);
    }
    return states;
  }

  private int countFailedUnits(String output) {
    if (output == null || output.isBlank()) {
      return 0;
    }
    int count = 0;
    for (String line : output.lines().toList()) {
      final String normalized = line == null ? "" : line.trim();
      if (normalized.isBlank()) {
        continue;
      }
      final String[] columns = normalized.split("\\s+");
      if (columns.length >= 4 && "failed".equalsIgnoreCase(columns[2])) {
        count++;
      }
    }
    return count;
  }

  private boolean startsWithNumericToken(String value) {
    final String[] columns = value.split("\\s+");
    if (columns.length == 0 || columns[0].isBlank()) {
      return false;
    }
    for (int i = 0; i < columns[0].length(); i++) {
      if (!Character.isDigit(columns[0].charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private String normalizeState(String value) {
    if (value == null) {
      return "unknown";
    }
    final String trimmed = value.trim().toLowerCase();
    if (trimmed.isBlank()) {
      return "unknown";
    }
    return trimmed;
  }
}
