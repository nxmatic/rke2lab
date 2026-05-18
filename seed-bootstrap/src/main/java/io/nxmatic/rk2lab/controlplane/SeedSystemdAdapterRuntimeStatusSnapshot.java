package io.nxmatic.rk2lab.controlplane;

import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
    final ProcessBuilder processBuilder =
        new ProcessBuilder("sh", "-lc", buildProbeCommand(config));
    processBuilder.environment().putIfAbsent("LANG", "C");

    try {
      final Process process = processBuilder.start();
      final boolean exited = process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        return envelope(
            "timeout",
            "adapter probe timed out after " + PROBE_TIMEOUT,
            Map.of("source", "systemd-adapter", "timeout", PROBE_TIMEOUT.toString()));
      }

      final String stdout =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      final String stderr =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();

      if (process.exitValue() != 0) {
        return envelope(
            "command-failed",
            "systemd local probe command failed",
            Map.of(
                "source",
                "systemd-local-probe",
                "exitCode",
                process.exitValue(),
                "stderr",
                summarizeFirstLine(stderr),
                "stdout",
                summarizeFirstLine(stdout)));
      }

      final Map<String, String> parsedProbeOutput = parseProbeOutput(stdout);
      if (parsedProbeOutput.isEmpty()) {
        return envelope(
            "invalid-probe-payload",
            "systemd local probe returned invalid payload",
            Map.of(
                "source",
                "systemd-local-probe",
                "probeMode",
                "host-local-systemd",
                "payloadFirstLine",
                summarizeFirstLine(stdout)));
      }

      final String observedAt =
          parsedProbeOutput.getOrDefault("observedAt", Instant.now().toString());
      final String mandatoryTarget =
          parsedProbeOutput.getOrDefault("mandatoryTarget", MANDATORY_TARGET);
      final String mandatoryTargetState =
          parsedProbeOutput.getOrDefault("mandatoryTargetState", "unknown");
      final int pendingJobs = parseNonNegativeInt(parsedProbeOutput.get("pendingJobs"), 0);
      final int failedUnits = parseNonNegativeInt(parsedProbeOutput.get("failedUnits"), 0);
      final boolean mandatoryTargetHealthy =
          parseBoolean(
              parsedProbeOutput.get("mandatoryTargetHealthy"),
              "active".equalsIgnoreCase(mandatoryTargetState));
      final boolean runtimePrecheckReady =
          parseBoolean(
              parsedProbeOutput.get("runtimePrecheckReady"),
              mandatoryTargetHealthy && pendingJobs == 0 && failedUnits == 0);

      final LinkedHashMap<String, Integer> jobsByState = new LinkedHashMap<>();
      if (pendingJobs > 0) {
        jobsByState.put("pending", pendingJobs);
      }

      final LinkedHashMap<String, String> connectionContext = new LinkedHashMap<>();
      connectionContext.put(
          "nixosHost", parsedProbeOutput.getOrDefault("nixosHost", config.imageBuilderHost()));
      connectionContext.put(
          "incusInstance", parsedProbeOutput.getOrDefault("incusInstance", config.nodeName()));
      connectionContext.put(
          "adapterHost", parsedProbeOutput.getOrDefault("adapterHost", "unknown"));
      connectionContext.put(
          "systemBusAddress",
          parsedProbeOutput.getOrDefault(
              "systemBusAddress", "unix:path=/var/run/dbus/system_bus_socket"));

      final String summary =
          parsedProbeOutput.getOrDefault(
              "summary",
              "mandatoryTarget="
                  + mandatoryTarget
                  + "(state="
                  + mandatoryTargetState
                  + "), pendingJobs="
                  + pendingJobs
                  + ", failedUnits="
                  + failedUnits
                  + ", source=host-local-systemd");

      final SystemdStatusSnapshot statusSnapshot =
          new SystemdStatusSnapshot(
              observedAt,
              mandatoryTarget,
              mandatoryTargetState,
              mandatoryTargetHealthy,
              pendingJobs,
              Map.copyOf(jobsByState),
              failedUnits,
              runtimePrecheckReady,
              Map.copyOf(connectionContext),
              summary);

      final LinkedHashMap<String, Object> parsed =
          new LinkedHashMap<>(statusSnapshot.toPayloadMap());
      parsed.put("apiVersion", API_VERSION);
      parsed.put("kind", KIND);
      parsed.put("source", "systemd-local-probe");
      parsed.put("probeMode", "host-local-systemd");
      parsed.put("status", "ok");
      parsed.putIfAbsent("capturedAt", Instant.now().toString());
      parsed.putIfAbsent("summary", "systemd local probe captured host runtime state");

      if (logger != null) {
        logger.accept("systemd adapter runtime summary: " + parsed.getOrDefault("summary", "n/a"));
      }
      return Map.copyOf(parsed);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return envelope(
          "interrupted",
          "systemd local probe interrupted",
          Map.of("source", "systemd-local-probe"));
    } catch (IOException ex) {
      return envelope(
          "execution-error",
          "systemd local probe execution error: " + ex.getMessage(),
          Map.of("source", "systemd-local-probe"));
    }
  }

  public static Map<String, Object> snapshotStandalone(BootstrapConfig config) {
    return snapshot(config, message -> System.out.println("[readiness] " + message));
  }

  private static String buildProbeCommand(BootstrapConfig config) {
    final String script =
        "set -eu\n"
            + "mandatory_target="
            + shellQuote(MANDATORY_TARGET)
            + "\n"
            + "mandatory_target_state=\"$(systemctl show --property=ActiveState --value \"$mandatory_target\" 2>/dev/null || true)\"\n"
            + "if [ -z \"$mandatory_target_state\" ]; then mandatory_target_state=unknown; fi\n"
            + "pending_jobs=\"$(systemctl show --property=NJobs --value 2>/dev/null || echo 0)\"\n"
            + "failed_units=\"$(systemctl show --property=NFailedUnits --value 2>/dev/null || echo 0)\"\n"
            + "adapter_host=\"$(hostname 2>/dev/null || echo unknown)\"\n"
            + "observed_at=\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"\n"
            + "nixos_host="
            + shellQuote(config.imageBuilderHost())
            + "\n"
            + "incus_instance="
            + shellQuote(config.nodeName())
            + "\n"
            + "if [ \"$mandatory_target_state\" = \"active\" ]; then mandatory_target_healthy=true; else mandatory_target_healthy=false; fi\n"
            + "if [ \"$mandatory_target_healthy\" = \"true\" ] && [ \"${pending_jobs:-0}\" = \"0\" ] && [ \"${failed_units:-0}\" = \"0\" ]; then runtime_precheck_ready=true; else runtime_precheck_ready=false; fi\n"
            + "summary=\"mandatoryTarget=$mandatory_target(state=$mandatory_target_state), pendingJobs=$pending_jobs, failedUnits=$failed_units, nixosHost=$nixos_host, incusInstance=$incus_instance, adapterHost=$adapter_host, source=host-local-systemd\"\n"
            + "printf '%s\\n' \"observedAt=$observed_at\"\n"
            + "printf '%s\\n' \"mandatoryTarget=$mandatory_target\"\n"
            + "printf '%s\\n' \"mandatoryTargetState=$mandatory_target_state\"\n"
            + "printf '%s\\n' \"mandatoryTargetHealthy=$mandatory_target_healthy\"\n"
            + "printf '%s\\n' \"pendingJobs=$pending_jobs\"\n"
            + "printf '%s\\n' \"failedUnits=$failed_units\"\n"
            + "printf '%s\\n' \"runtimePrecheckReady=$runtime_precheck_ready\"\n"
            + "printf '%s\\n' \"nixosHost=$nixos_host\"\n"
            + "printf '%s\\n' \"incusInstance=$incus_instance\"\n"
            + "printf '%s\\n' \"adapterHost=$adapter_host\"\n"
            + "printf '%s\\n' \"systemBusAddress=unix:path=/var/run/dbus/system_bus_socket\"\n"
            + "printf '%s\\n' \"summary=$summary\"\n";

    final String remoteIncusCommand =
        "incus --project "
            + shellQuote(config.incusProject())
            + " exec "
            + " "
            + shellQuote(config.nodeName())
            + " -- sh -lc "
            + shellQuote(script);

    return "ssh -o BatchMode=yes -o ConnectTimeout=10 "
        + shellQuote(config.imageBuilderHost())
        + " sh -lc "
        + shellQuote(remoteIncusCommand);
  }

  private static String summarizeFirstLine(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
  }

  private static Map<String, String> parseProbeOutput(String stdout) {
    if (stdout == null || stdout.isBlank()) {
      return Map.of();
    }

    final List<String> lines = stdout.lines().toList();
    final LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
    for (String rawLine : lines) {
      if (rawLine == null) {
        continue;
      }
      final String line = rawLine.trim();
      if (line.isBlank()) {
        continue;
      }
      final int separatorIndex = line.indexOf('=');
      if (separatorIndex <= 0) {
        continue;
      }
      final String key = line.substring(0, separatorIndex).trim();
      final String value = line.substring(separatorIndex + 1).trim();
      if (key.isBlank()) {
        continue;
      }
      parsed.put(key, value);
    }

    return parsed.isEmpty() ? Map.of() : Map.copyOf(parsed);
  }

  private static int parseNonNegativeInt(String rawValue, int fallback) {
    if (rawValue == null || rawValue.isBlank()) {
      return Math.max(fallback, 0);
    }

    try {
      return Math.max(Integer.parseInt(rawValue.trim()), 0);
    } catch (NumberFormatException ignored) {
      return Math.max(fallback, 0);
    }
  }

  private static boolean parseBoolean(String rawValue, boolean fallback) {
    if (rawValue == null || rawValue.isBlank()) {
      return fallback;
    }

    return Boolean.parseBoolean(rawValue.trim());
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
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
}
