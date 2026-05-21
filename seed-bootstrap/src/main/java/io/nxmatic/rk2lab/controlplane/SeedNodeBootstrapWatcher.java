package io.nxmatic.rk2lab.controlplane;

import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import java.time.Duration;
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
    effectiveLogger.accept("waiting for seed node systemd adapter runtime status...");

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
      final String pendingDependencies = describePendingDependencies(statusSnapshot);

      if ("ok".equalsIgnoreCase(probeStatus) && runtimeReady) {
        effectiveLogger.accept(
            "seed node bootstrap preconditions ready after " + elapsedSince(startedAt));
        return true;
      }

      lastSummary =
          "status="
              + probeStatus
              + ", mandatoryTarget="
              + mandatoryTarget
              + "="
              + mandatoryTargetState
              + ", pendingJobs="
              + pendingJobCount
              + ", failedUnits="
              + failedUnitCount
              + ", hostContext="
              + hostContext
              + ", pendingDependencies="
              + pendingDependencies
              + ", summary="
              + adapterSummary;

      final long now = System.nanoTime();
      if (now >= nextProgressLogAt) {
        effectiveLogger.accept(
            "still waiting for seed node bootstrap preconditions after "
                + elapsedSince(startedAt)
                + " ("
                + lastSummary
                + ")");
        nextProgressLogAt = now + progressLogInterval.toNanos();
      }

      sleep(retryInterval);
    }

    effectiveLogger.accept(
        "seed node bootstrap precondition wait timed out after "
            + timeout
            + " (last result: "
            + lastSummary
            + ")");
    return false;
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

  private static String describePendingDependencies(Map<String, Object> statusSnapshot) {
    final Object raw = statusSnapshot.get("pendingDependencies");
    if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
      return "none";
    }

    final StringBuilder builder = new StringBuilder("[");
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        builder.append(',');
      }
      first = false;
      builder.append(entry.getKey()).append('=').append(entry.getValue());
    }
    builder.append(']');
    return builder.toString();
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
