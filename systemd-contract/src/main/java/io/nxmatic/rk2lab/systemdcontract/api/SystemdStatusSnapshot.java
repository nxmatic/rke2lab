// @codebase
package io.nxmatic.rk2lab.systemdcontract.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record SystemdStatusSnapshot(
    String observedAt,
    String mandatoryTarget,
    String mandatoryTargetState,
    boolean mandatoryTargetHealthy,
    int pendingJobs,
    Map<String, Integer> jobsByState,
    int failedUnits,
    boolean runtimePrecheckReady,
    Map<String, String> connectionContext,
    String summary) {

  public SystemdStatusSnapshot {
    observedAt = normalizeObservedAt(observedAt);
    mandatoryTarget = normalizeString(mandatoryTarget, "rke2lab.target");
    mandatoryTargetState = normalizeString(mandatoryTargetState, "unknown");
    pendingJobs = Math.max(pendingJobs, 0);
    jobsByState = sanitizeIntegerMap(jobsByState);
    failedUnits = Math.max(failedUnits, 0);
    connectionContext = sanitizeStringMap(connectionContext);
    summary = normalizeString(summary, "n/a");
  }

  public static SystemdStatusSnapshot fromPayloadMap(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return new SystemdStatusSnapshot(
          Instant.now().toString(),
          "rke2lab.target",
          "unknown",
          false,
          0,
          Map.of(),
          0,
          false,
          Map.of(),
          "empty status payload");
    }

    return new SystemdStatusSnapshot(
        asString(payload.get("observedAt"), Instant.now().toString()),
        asString(payload.get("mandatoryTarget"), "rke2lab.target"),
        asString(payload.get("mandatoryTargetState"), "unknown"),
        asBoolean(payload.get("mandatoryTargetHealthy"), false),
        asInt(payload.get("pendingJobs"), 0),
        asIntegerMap(payload.get("jobsByState")),
        asInt(payload.get("failedUnits"), 0),
        asBoolean(payload.get("runtimePrecheckReady"), false),
        asStringMap(payload.get("connectionContext")),
        asString(payload.get("summary"), "n/a"));
  }

  public Map<String, Object> toPayloadMap() {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("observedAt", observedAt);
    payload.put("mandatoryTarget", mandatoryTarget);
    payload.put("mandatoryTargetState", mandatoryTargetState);
    payload.put("mandatoryTargetHealthy", mandatoryTargetHealthy);
    payload.put("pendingJobs", pendingJobs);
    payload.put("jobsByState", jobsByState);
    payload.put("failedUnits", failedUnits);
    payload.put("runtimePrecheckReady", runtimePrecheckReady);
    payload.put("connectionContext", connectionContext);
    payload.put("summary", summary);
    return Map.copyOf(payload);
  }

  private static String normalizeObservedAt(String value) {
    if (value == null || value.isBlank()) {
      return Instant.now().toString();
    }
    return value.trim();
  }

  private static String normalizeString(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }

  private static String asString(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    final String raw = value.toString().trim();
    if (raw.isBlank()) {
      return fallback;
    }
    return raw;
  }

  private static boolean asBoolean(Object value, boolean fallback) {
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    if (value == null) {
      return fallback;
    }
    return Boolean.parseBoolean(value.toString().trim());
  }

  private static int asInt(Object value, int fallback) {
    if (value instanceof Number numberValue) {
      return Math.max(numberValue.intValue(), 0);
    }
    if (value == null) {
      return Math.max(fallback, 0);
    }

    final String raw = value.toString().trim();
    if (raw.isBlank()) {
      return Math.max(fallback, 0);
    }

    try {
      return Math.max(Integer.parseInt(raw), 0);
    } catch (NumberFormatException ignored) {
      return Math.max(fallback, 0);
    }
  }

  private static Map<String, Integer> asIntegerMap(Object value) {
    if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
      return Map.of();
    }

    final LinkedHashMap<String, Integer> parsed = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      if (entry.getKey() == null) {
        continue;
      }
      final String key = entry.getKey().toString().trim();
      if (key.isBlank()) {
        continue;
      }
      parsed.put(key, asInt(entry.getValue(), 0));
    }
    return Map.copyOf(parsed);
  }

  private static Map<String, String> asStringMap(Object value) {
    if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
      return Map.of();
    }

    final LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      if (entry.getKey() == null) {
        continue;
      }
      final String key = entry.getKey().toString().trim();
      if (key.isBlank()) {
        continue;
      }
      parsed.put(key, asString(entry.getValue(), "unknown"));
    }
    return Map.copyOf(parsed);
  }

  private static Map<String, Integer> sanitizeIntegerMap(Map<String, Integer> value) {
    if (value == null || value.isEmpty()) {
      return Map.of();
    }

    final LinkedHashMap<String, Integer> sanitized = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : value.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        continue;
      }
      sanitized.put(entry.getKey(), Math.max(entry.getValue() == null ? 0 : entry.getValue(), 0));
    }
    return Map.copyOf(sanitized);
  }

  private static Map<String, String> sanitizeStringMap(Map<String, String> value) {
    if (value == null || value.isEmpty()) {
      return Map.of();
    }

    final LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : value.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        continue;
      }
      sanitized.put(entry.getKey(), normalizeString(entry.getValue(), "unknown"));
    }
    return Map.copyOf(sanitized);
  }
}
