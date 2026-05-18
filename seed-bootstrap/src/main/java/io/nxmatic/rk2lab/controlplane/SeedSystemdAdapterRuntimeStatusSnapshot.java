package io.nxmatic.rk2lab.controlplane;

import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Canonical runtime status snapshot probe backed by host-local systemd state. */
public final class SeedSystemdAdapterRuntimeStatusSnapshot {

  private static final String API_VERSION = "rk2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterRuntimeStatus";
  private static final String MANDATORY_TARGET = "rke2lab.target";
  private static final String MANDATORY_CLOUD_INIT_UNIT = "cloud-init-main.service";
  private static final String UNKNOWN_STATE = "unknown";
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

  private SeedSystemdAdapterRuntimeStatusSnapshot() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return envelope(
        "deferred-preview",
        "runtime status deferred during preview; probe=host-local-systemd",
        Map.of(
            "source",
            "systemd-local-probe",
            "probeMode",
            "host-local-systemd",
            "mandatoryTarget",
            MANDATORY_TARGET));
  }

  public static Map<String, Object> snapshot(BootstrapConfig config, Consumer<String> logger) {
    try {
      final CommandResult targetStateResult =
          runCommandInInstance(
              config, "systemctl show --property=ActiveState --value " + MANDATORY_TARGET);
      final String mandatoryTargetState =
          readMandatoryTargetState(config, targetStateResult, logger, UNKNOWN_STATE);
      final int pendingJobs = readIntProperty(config, "NJobs", "pending-jobs", 0, logger);
      final int failedUnits = readIntProperty(config, "NFailedUnits", "failed-units", 0, logger);
      final String adapterHost =
          sanitizeHostLabel(
              readOptionalValue(config, "adapter-host", config.nodeName(), "hostname"),
              config.nodeName());
      final JobsProbe currentJobsProbe = readCurrentJobsProbe(config);
      final String currentJobsSummary = currentJobsProbe.summary();
      final String currentStartingService = currentJobsProbe.currentStartingService();
      final String currentActiveUnits = readCurrentActiveUnitsSummary(config);
      final String targetWantsSummary = readTargetWantsSummary(config);
      final UnitHealth cloudInitMain = readMandatoryUnitHealth(config, MANDATORY_CLOUD_INIT_UNIT);

      final boolean mandatoryTargetHealthy = "active".equalsIgnoreCase(mandatoryTargetState);
      final boolean runtimePrecheckReady =
          mandatoryTargetHealthy && pendingJobs == 0 && failedUnits == 0 && cloudInitMain.healthy();

      final LinkedHashMap<String, Integer> jobsByState = new LinkedHashMap<>();
      if (pendingJobs > 0) {
        jobsByState.put("pending", pendingJobs);
      }

      final LinkedHashMap<String, String> connectionContext = new LinkedHashMap<>();
      connectionContext.put("nixosHost", config.imageBuilderHost());
      connectionContext.put("incusInstance", config.nodeName());
      connectionContext.put("adapterHost", adapterHost);
      connectionContext.put("systemBusAddress", "unix:path=/var/run/dbus/system_bus_socket");

      final String summary =
          "mandatoryTarget="
              + MANDATORY_TARGET
              + "(state="
              + mandatoryTargetState
              + "), pendingJobs="
              + pendingJobs
              + ", failedUnits="
              + failedUnits
              + ", cloudInitMain="
              + cloudInitMain.unitName()
              + "(state="
              + cloudInitMain.activeState()
              + ",result="
              + cloudInitMain.result()
              + ",healthy="
              + cloudInitMain.healthy()
              + ")"
              + ", nixosHost="
              + config.imageBuilderHost()
              + ", incusInstance="
              + config.nodeName()
              + ", adapterHost="
              + adapterHost
              + ", jobs="
              + currentJobsSummary
              + ", currentStartingService="
              + currentStartingService
              + ", activeUnits="
              + currentActiveUnits
              + ", wants="
              + targetWantsSummary
              + ", source=host-local-systemd";

      final SystemdStatusSnapshot statusSnapshot =
          SystemdStatusSnapshot.builder()
              .observedAt(Instant.now().toString())
              .mandatoryTarget(MANDATORY_TARGET)
              .mandatoryTargetState(mandatoryTargetState)
              .mandatoryTargetHealthy(mandatoryTargetHealthy)
              .pendingJobs(pendingJobs)
              .jobsByState(Map.copyOf(jobsByState))
              .failedUnits(failedUnits)
              .runtimePrecheckReady(runtimePrecheckReady)
              .connectionContext(Map.copyOf(connectionContext))
              .summary(summary)
              .build();

      final LinkedHashMap<String, Object> parsed =
          new LinkedHashMap<>(statusSnapshot.toPayloadMap());
      parsed.put("apiVersion", API_VERSION);
      parsed.put("kind", KIND);
      parsed.put("source", "systemd-local-probe");
      parsed.put("probeMode", "host-local-systemd");
      parsed.put("status", "ok");
      parsed.put("currentJobs", currentJobsSummary);
      parsed.put("currentStartingService", currentStartingService);
      parsed.put("currentActiveUnits", currentActiveUnits);
      parsed.put("targetWants", targetWantsSummary);
      parsed.put("cloudInitMainState", cloudInitMain.activeState());
      parsed.put("cloudInitMainResult", cloudInitMain.result());
      parsed.put("cloudInitMainHealthy", cloudInitMain.healthy());
      parsed.putIfAbsent("capturedAt", Instant.now().toString());
      parsed.putIfAbsent("summary", "systemd local probe captured host runtime state");

      if (logger != null) {
        logger.accept("systemd adapter runtime summary: " + parsed.getOrDefault("summary", "n/a"));
      }
      return Map.copyOf(parsed);
    } catch (IllegalStateException ex) {
      return envelope(
          "execution-error",
          "systemd local probe execution error: " + ex.getMessage(),
          Map.of("source", "systemd-local-probe"));
    }
  }

  public static Map<String, Object> snapshotStandalone(BootstrapConfig config) {
    return snapshot(config, message -> System.out.println("[readiness] " + message));
  }

  private static List<String> incusExec(BootstrapConfig config, String... args) {
    final ArrayList<String> command = new ArrayList<>();
    command.add("ssh");
    command.add("-o");
    command.add("BatchMode=yes");
    command.add("-o");
    command.add("ConnectTimeout=10");
    command.add(config.imageBuilderHost());
    command.add("incus");
    command.add("--project");
    command.add(config.incusProject());
    command.add("exec");
    command.add(config.nodeName());
    command.add("--");
    if (args != null) {
      for (String arg : args) {
        command.add(arg == null ? "" : arg);
      }
    }
    return List.copyOf(command);
  }

  private static String summarizeFirstLine(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
  }

  private static int readIntProperty(
      BootstrapConfig config, String property, String step, int fallback, Consumer<String> logger) {
    final CommandResult result =
        runCommandInInstance(config, "systemctl show --property=" + property + " --value");
    if (looksLikeIncusHelp(result.stdout())) {
      if (logger != null) {
        logger.accept(
            "systemd local probe warning: got Incus CLI help while reading "
                + property
                + ", using fallback="
                + fallback);
      }
      return fallback;
    }
    if (result.exitCode() != 0) {
      if (logger != null) {
        logger.accept(
            "systemd local probe warning: failed reading "
                + property
                + " ("
                + summarizeFirstLine(result.stderr())
                + "), using fallback="
                + fallback);
      }
      return fallback;
    }

    final String raw = firstNonBlankLine(result.stdout(), String.valueOf(fallback));
    try {
      return Math.max(Integer.parseInt(raw.trim()), 0);
    } catch (NumberFormatException ignored) {
      if (logger != null) {
        logger.accept(
            "systemd local probe warning: invalid "
                + property
                + " value='"
                + raw
                + "', using fallback="
                + fallback);
      }
      return fallback;
    }
  }

  private static String readOptionalValue(
      BootstrapConfig config, String step, String fallback, String... args) {
    final CommandResult result = runCommand(incusExec(config, args));
    if (looksLikeIncusHelp(result.stdout())) {
      return fallback;
    }
    if (result.exitCode() != 0) {
      return fallback;
    }
    return firstNonBlankLine(result.stdout(), fallback);
  }

  private static JobsProbe readCurrentJobsProbe(BootstrapConfig config) {
    final CommandResult result = runCommandInInstance(config, "systemctl list-jobs --no-pager");
    if (looksLikeIncusHelp(result.stdout())) {
      return new JobsProbe("unavailable", "unavailable");
    }
    if (result.exitCode() != 0) {
      return new JobsProbe("unavailable", "unavailable");
    }

    final String output = result.stdout() == null ? "" : result.stdout().trim();
    if (output.isBlank()) {
      return new JobsProbe("none", "none");
    }
    if (output.contains("No jobs running.")) {
      return new JobsProbe("none", "none");
    }

    final List<String> allLines =
        output.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
    if (allLines.isEmpty()) {
      return new JobsProbe("none", "none");
    }

    final ArrayList<String> normalized = new ArrayList<>(allLines);
    if (!normalized.isEmpty() && normalized.getFirst().toLowerCase().startsWith("job ")) {
      normalized.removeFirst();
    }
    if (!normalized.isEmpty()
        && normalized.getLast().toLowerCase().matches("^\\d+\\s+jobs?\\s+listed\\.?$")) {
      normalized.removeLast();
    }
    if (normalized.isEmpty()) {
      return new JobsProbe("none", "none");
    }

    final String summary = String.join(" | ", normalized.stream().limit(3).toList());
    return new JobsProbe(summary, extractCurrentStartingService(normalized));
  }

  private static String extractCurrentStartingService(List<String> jobLines) {
    if (jobLines == null || jobLines.isEmpty()) {
      return "none";
    }

    for (String line : jobLines) {
      final String[] parts = line.trim().split("\\s+");
      if (parts.length < 4) {
        continue;
      }

      final String unit = parts[1];
      final String type = parts[2].toLowerCase();
      final String state = parts[3].toLowerCase();
      if (("start".equals(type) || "restart".equals(type))
          && ("waiting".equals(state) || "running".equals(state))) {
        return unit;
      }
    }

    final String[] first = jobLines.getFirst().split("\\s+");
    if (first.length >= 2) {
      return first[1];
    }
    return "unknown";
  }

  private static String readCurrentActiveUnitsSummary(BootstrapConfig config) {
    final CommandResult result =
        runCommandInInstance(
            config,
            "systemctl list-units --all --plain --no-pager --no-legend --type=service --type=target 'rke2lab*' rke2-server.service");
    if (looksLikeIncusHelp(result.stdout())) {
      return "unavailable";
    }
    if (result.exitCode() != 0) {
      return "unavailable";
    }

    final String output = result.stdout() == null ? "" : result.stdout().trim();
    if (output.isBlank()) {
      return "none";
    }

    final List<String> activeUnits = new ArrayList<>();
    for (String rawLine : output.lines().toList()) {
      final String line = rawLine == null ? "" : rawLine.trim();
      if (line.isBlank()) {
        continue;
      }

      final String[] parts = line.split("\\s+");
      if (parts.length < 4) {
        continue;
      }

      final String unit = parts[0];
      final String activeState = parts[2].toLowerCase();
      final String subState = parts[3].toLowerCase();
      if (!"active".equals(activeState) && !"activating".equals(activeState)) {
        continue;
      }
      if ("dead".equals(subState) || "exited".equals(subState)) {
        continue;
      }
      activeUnits.add(unit + "(" + subState + ")");
      if (activeUnits.size() >= 8) {
        break;
      }
    }

    if (activeUnits.isEmpty()) {
      return "none";
    }
    return String.join(", ", activeUnits);
  }

  private static String readTargetWantsSummary(BootstrapConfig config) {
    final CommandResult result =
        runCommandInInstance(config, "systemctl show --property=Wants --value " + MANDATORY_TARGET);
    if (looksLikeIncusHelp(result.stdout())) {
      return "unavailable";
    }
    if (result.exitCode() != 0) {
      return "unavailable";
    }

    final String raw = firstNonBlankLine(result.stdout(), "").trim();
    if (raw.isBlank()) {
      return "0";
    }

    final List<String> units =
        raw.lines()
            .flatMap(line -> List.of(line.split("\\s+")).stream())
            .filter(unit -> !unit.isBlank())
            .toList();
    return String.valueOf(units.size());
  }

  private static UnitHealth readMandatoryUnitHealth(BootstrapConfig config, String unitName) {
    final String activeState =
        readUnitProperty(config, unitName, "ActiveState", "unknown", false).toLowerCase();
    final String result =
        readUnitProperty(config, unitName, "Result", "unknown", true).toLowerCase();
    final String loadState =
        readUnitProperty(config, unitName, "LoadState", "not-found", false).toLowerCase();

    final boolean present = !"not-found".equals(loadState);
    final boolean activeOrCompleted =
        "active".equals(activeState)
            || "inactive".equals(activeState)
            || "activating".equals(activeState);
    final boolean successfulResult =
        "success".equals(result) || "unknown".equals(result) || "".equals(result);

    final boolean healthy = present && activeOrCompleted && successfulResult;
    return new UnitHealth(unitName, activeState, result, healthy);
  }

  private static String readUnitProperty(
      BootstrapConfig config,
      String unitName,
      String property,
      String fallback,
      boolean allowBlankAsSuccess) {
    final CommandResult result =
        runCommandInInstance(
            config, "systemctl show --property=" + property + " --value " + unitName);
    if (looksLikeIncusHelp(result.stdout()) || result.exitCode() != 0) {
      return fallback;
    }

    final String raw =
        firstNonBlankLine(result.stdout(), allowBlankAsSuccess ? "" : fallback).trim();
    if (!allowBlankAsSuccess && raw.isBlank()) {
      return fallback;
    }
    return raw;
  }

  private static String normalizeTargetState(String rawState) {
    if (rawState == null || rawState.isBlank()) {
      return UNKNOWN_STATE;
    }

    final String normalized = rawState.trim().toLowerCase();
    return switch (normalized) {
      case "active", "inactive", "failed", "activating", "deactivating", "reloading" -> normalized;
      default -> UNKNOWN_STATE;
    };
  }

  private static String readMandatoryTargetState(
      BootstrapConfig config,
      CommandResult targetStateResult,
      Consumer<String> logger,
      String fallback) {
    if (looksLikeIncusHelp(targetStateResult.stdout())) {
      if (logger != null) {
        logger.accept(
            "systemd local probe warning: got Incus CLI help while reading ActiveState for "
                + MANDATORY_TARGET
                + ", using fallback="
                + fallback);
      }
      return fallback;
    }

    if (targetStateResult.exitCode() != 0) {
      if (logger != null) {
        logger.accept(
            "systemd local probe warning: failed reading ActiveState for "
                + MANDATORY_TARGET
                + " ("
                + summarizeFirstLine(targetStateResult.stderr())
                + "), using fallback="
                + fallback
                + ", nixosHost="
                + config.imageBuilderHost()
                + ", incusInstance="
                + config.nodeName());
      }
      return fallback;
    }

    return normalizeTargetState(firstNonBlankLine(targetStateResult.stdout(), fallback));
  }

  private static String sanitizeHostLabel(String rawValue, String fallback) {
    if (rawValue == null || rawValue.isBlank()) {
      return fallback;
    }

    final String candidate = rawValue.trim();
    if (candidate.contains(":")) {
      return fallback;
    }
    if (!candidate.matches("[A-Za-z0-9._-]+")) {
      return fallback;
    }
    return candidate;
  }

  private static String firstNonBlankLine(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }

    return value
        .lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .findFirst()
        .orElse(fallback);
  }

  private static CommandResult runCommand(List<String> command) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.environment().putIfAbsent("LANG", "C");
    try {
      final Process process = processBuilder.start();
      final boolean exited = process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        return new CommandResult(-1, "", "timed out after " + PROBE_TIMEOUT);
      }

      final String stdout =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final String stderr =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      return new CommandResult(process.exitValue(), stdout, stderr);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return new CommandResult(-1, "", "command interrupted");
    } catch (IOException ex) {
      return new CommandResult(-1, "", "failed to execute command: " + ex.getMessage());
    }
  }

  private static CommandResult runCommandInInstance(BootstrapConfig config, String script) {
    return runCommand(incusExec(config, "sh", "-lc", script));
  }

  private static boolean looksLikeIncusHelp(String output) {
    if (output == null || output.isBlank()) {
      return false;
    }

    final String normalized = output.toLowerCase();
    return normalized.contains("command line client for incus")
        || (normalized.contains("usage:") && normalized.contains("incus"));
  }

  private static Map<String, Object> envelope(
      String status, String summary, Map<String, Object> details) {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("apiVersion", API_VERSION);
    payload.put("kind", KIND);
    payload.put("status", status);
    payload.put("summary", summary);
    payload.put("capturedAt", Instant.now().toString());
    if (details != null && !details.isEmpty()) {
      payload.putAll(details);
    }
    return Map.copyOf(payload);
  }

  private record CommandResult(int exitCode, String stdout, String stderr) {}

  private record JobsProbe(String summary, String currentStartingService) {}

  private record UnitHealth(String unitName, String activeState, String result, boolean healthy) {}
}
