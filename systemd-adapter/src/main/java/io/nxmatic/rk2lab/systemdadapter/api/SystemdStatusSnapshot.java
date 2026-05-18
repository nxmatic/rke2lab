package io.nxmatic.rk2lab.systemdadapter.api;

import java.time.Instant;
import java.util.Map;

public record SystemdStatusSnapshot(
    Instant observedAt,
    String mandatoryTarget,
    String mandatoryTargetState,
    boolean mandatoryTargetHealthy,
    int pendingJobs,
    Map<String, Integer> jobsByState,
    int failedUnits,
    boolean runtimePrecheckReady,
    String summary) {}
