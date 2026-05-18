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

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String observedAt = Instant.now().toString();
    private String mandatoryTarget = "rke2lab.target";
    private String mandatoryTargetState = "unknown";
    private boolean mandatoryTargetHealthy;
    private int pendingJobs;
    private Map<String, Integer> jobsByState = Map.of();
    private int failedUnits;
    private boolean runtimePrecheckReady;
    private Map<String, String> connectionContext = Map.of();
    private String summary = "n/a";

    private Builder() {}

    public Builder observedAt(String value) {
      this.observedAt = value;
      return this;
    }

    public Builder mandatoryTarget(String value) {
      this.mandatoryTarget = value;
      return this;
    }

    public Builder mandatoryTargetState(String value) {
      this.mandatoryTargetState = value;
      return this;
    }

    public Builder mandatoryTargetHealthy(boolean value) {
      this.mandatoryTargetHealthy = value;
      return this;
    }

    public Builder pendingJobs(int value) {
      this.pendingJobs = value;
      return this;
    }

    public Builder jobsByState(Map<String, Integer> value) {
      this.jobsByState = value;
      return this;
    }

    public Builder failedUnits(int value) {
      this.failedUnits = value;
      return this;
    }

    public Builder runtimePrecheckReady(boolean value) {
      this.runtimePrecheckReady = value;
      return this;
    }

    public Builder connectionContext(Map<String, String> value) {
      this.connectionContext = value;
      return this;
    }

    public Builder summary(String value) {
      this.summary = value;
      return this;
    }

    public SystemdStatusSnapshot build() {
      return new SystemdStatusSnapshot(
          observedAt,
          mandatoryTarget,
          mandatoryTargetState,
          mandatoryTargetHealthy,
          pendingJobs,
          jobsByState,
          failedUnits,
          runtimePrecheckReady,
          connectionContext,
          summary);
    }
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
