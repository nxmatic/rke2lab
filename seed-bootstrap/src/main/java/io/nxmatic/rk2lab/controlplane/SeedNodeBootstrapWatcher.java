package io.nxmatic.rk2lab.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Watches seed-node runtime/systemd bootstrap preconditions and reports progress while waiting for
 * convergence.
 */
public final class SeedNodeBootstrapWatcher {

  private SeedNodeBootstrapWatcher() {
    // Utility class
  }

  public static boolean waitForBootstrapPreconditions(
      BootstrapConfig config,
      Duration timeout,
      Duration retryInterval,
      Duration progressLogInterval,
      Consumer<String> logger) {
    final Consumer<String> effectiveLogger = logger == null ? message -> {} : logger;
    final Path snapshotPath = resolveSnapshotPath(config);
    effectiveLogger.accept(
        "waiting for seed node systemd adapter runtime status (snapshot=" + snapshotPath + ")...");

    final long startedAt = System.nanoTime();
    long nextProgressLogAt = startedAt + progressLogInterval.toNanos();
    final long deadlineNanos = System.nanoTime() + timeout.toNanos();

    String lastSummary = "not yet checked";
    while (System.nanoTime() < deadlineNanos) {
      final Map<String, Object> statusSnapshot =
          SeedSystemdAdapterRuntimeStatusSnapshot.snapshot(config, null);
      final String probeStatus = stringValue(statusSnapshot.getOrDefault("status", "unknown"));
      final boolean runtimeReady = toBoolean(statusSnapshot.get("runtimePrecheckReady"));
      final int pendingJobCount = toInt(statusSnapshot.get("pendingJobs"), -1);
      final int failedUnitCount = toInt(statusSnapshot.get("failedUnits"), -1);
      final String mandatoryTarget =
          stringValue(statusSnapshot.getOrDefault("mandatoryTarget", "rke2lab.target"));
      final String mandatoryTargetState =
          stringValue(statusSnapshot.getOrDefault("mandatoryTargetState", "unknown"));
      final String adapterSummary = stringValue(statusSnapshot.getOrDefault("summary", "n/a"));
      final String hostContext = describeHostContext(statusSnapshot);

      if ("ok".equalsIgnoreCase(probeStatus) && runtimeReady) {
        effectiveLogger.accept(
            "seed node bootstrap preconditions ready after " + elapsedSince(startedAt));
        return true;
      }

      lastSummary =
          renderYamlSummary(
              probeStatus,
              mandatoryTarget,
              mandatoryTargetState,
              pendingJobCount,
              failedUnitCount,
              hostContext,
              statusSnapshot,
              adapterSummary);
      writeSnapshot(snapshotPath, lastSummary, effectiveLogger);

      final long now = System.nanoTime();
      if (now >= nextProgressLogAt) {
        effectiveLogger.accept(
            "still waiting after "
                + elapsedSince(startedAt)
                + ": status="
                + probeStatus
                + " target="
                + mandatoryTarget
                + "("
                + mandatoryTargetState
                + ") pendingJobs="
                + pendingJobCount
                + " failedUnits="
                + failedUnitCount
                + " snapshot="
                + snapshotPath);
        nextProgressLogAt = now + progressLogInterval.toNanos();
      }

      sleep(retryInterval);
    }

    effectiveLogger.accept(
        "seed node bootstrap precondition wait timed out after "
            + timeout
            + "; last snapshot: "
            + snapshotPath);
    return false;
  }

  private static Path resolveSnapshotPath(BootstrapConfig config) {
    final Path worktreeRoot =
        Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    final String nodeName = config == null ? "seed" : nullSafe(config.nodeName(), "seed");
    return worktreeRoot
        .resolve(".local.d")
        .resolve("var")
        .resolve("run")
        .resolve("readiness-" + nodeName + ".yaml");
  }

  private static void writeSnapshot(Path target, String yaml, Consumer<String> logger) {
    try {
      Files.createDirectories(target.getParent());
      Files.writeString(target, yaml + System.lineSeparator(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      logger.accept("failed to write readiness snapshot to " + target + ": " + ex.getMessage());
    }
  }

  private static String nullSafe(String value, String fallback) {
    if (value == null) {
      return fallback;
    }
    final String trimmed = value.trim();
    return trimmed.isEmpty() ? fallback : trimmed;
  }

  private static final ObjectMapper YAML_MAPPER =
      new ObjectMapper(
          new YAMLFactory()
              .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
              .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
              .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR));

  private static String renderYamlSummary(
      String probeStatus,
      String mandatoryTarget,
      String mandatoryTargetState,
      int pendingJobCount,
      int failedUnitCount,
      String hostContext,
      Map<String, Object> statusSnapshot,
      String adapterSummary) {
    final Map<String, Object> root = new LinkedHashMap<>();
    root.put("status", probeStatus);
    final Map<String, Object> target = new LinkedHashMap<>();
    target.put("unit", mandatoryTarget);
    target.put("state", mandatoryTargetState);
    root.put("mandatoryTarget", target);
    root.put("pendingJobs", pendingJobCount);

    final Object pendingJobsDetail = statusSnapshot.get("pendingJobDetails");
    if (pendingJobsDetail instanceof Map<?, ?> pendingJobsMap && !pendingJobsMap.isEmpty()) {
      root.put("pendingJobDetails", pendingJobsMap);
    }

    root.put("failedUnits", failedUnitCount);

    final Object failedUnitsDetail = statusSnapshot.get("failedUnitDetails");
    if (failedUnitsDetail instanceof Map<?, ?> failedMap && !failedMap.isEmpty()) {
      root.put("failedUnitDetails", failedMap);
    }

    root.put("hostContext", hostContext);

    final Object rawPending = statusSnapshot.get("pendingDependencies");
    if (rawPending instanceof Map<?, ?> pendingMap && !pendingMap.isEmpty()) {
      root.put("pendingDependencies", pendingMap);
    } else {
      root.put("pendingDependencies", "none");
    }

    root.put("summary", adapterSummary);

    try {
      return YAML_MAPPER.writeValueAsString(root).stripTrailing();
    } catch (JsonProcessingException ex) {
      return root.toString();
    }
  }

  private static boolean toBoolean(Object value) {
    if (value instanceof Boolean boolValue) {
      return boolValue;
    }
    return Boolean.parseBoolean(stringValue(value));
  }

  private static int toInt(Object value, int fallback) {
    if (value instanceof Number numberValue) {
      return numberValue.intValue();
    }
    final String raw = stringValue(value);
    if (raw.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String stringValue(Object value) {
    return value == null ? "" : value.toString();
  }

  private static String describeHostContext(Map<String, Object> statusSnapshot) {
    final Object rawConnectionContext = statusSnapshot.get("connectionContext");
    if (!(rawConnectionContext instanceof Map<?, ?> connectionContext)) {
      return "nixosHost="
          + configFallbackString(statusSnapshot, "nixosHost")
          + ",incusInstance="
          + configFallbackString(statusSnapshot, "incusInstance")
          + ",adapterHost="
          + configFallbackString(statusSnapshot, "adapterHost");
    }

    return "nixosHost="
        + mapStringValue(connectionContext, "nixosHost")
        + ",incusInstance="
        + mapStringValue(connectionContext, "incusInstance")
        + ",adapterHost="
        + mapStringValue(connectionContext, "adapterHost");
  }

  private static String configFallbackString(Map<String, Object> statusSnapshot, String key) {
    final Object value = statusSnapshot.get(key);
    if (value == null) {
      return "unknown";
    }
    final String raw = value.toString().trim();
    if (raw.isBlank()) {
      return "unknown";
    }
    return raw;
  }

  private static String mapStringValue(Map<?, ?> map, String key) {
    if (map == null || key == null || key.isBlank()) {
      return "unknown";
    }

    final Object rawValue = map.get(key);
    if (rawValue == null) {
      return "unknown";
    }

    final String value = rawValue.toString().trim();
    if (value.isBlank()) {
      return "unknown";
    }
    return value;
  }

  private static String elapsedSince(long startedAtNanos) {
    return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos)).toString();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
