package io.nxmatic.rk2lab.controlplane;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdAdapterApiPaths;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Canonical runtime status snapshot probe backed by systemd-adapter API. */
public final class SeedSystemdAdapterRuntimeStatusSnapshot {

  private static final String API_VERSION = "rk2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterRuntimeStatus";
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
  private static final Gson GSON = new Gson();
  private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
  private static final String ADAPTER_BIND_HOST = "127.0.0.1";
  private static final int ADAPTER_PORT = 18080;
  private static final String ADAPTER_ENDPOINT_URL =
      "http://" + ADAPTER_BIND_HOST + ":" + ADAPTER_PORT + SystemdAdapterApiPaths.STATUS_SYSTEMD;

  private SeedSystemdAdapterRuntimeStatusSnapshot() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return envelope(
        "deferred-preview",
        "runtime status deferred during preview; probe=systemd-adapter-http",
        Map.of(
            "source",
            "systemd-adapter-http-probe",
            "probeMode",
            "systemd-adapter-http",
            "adapterEndpoint",
            ADAPTER_ENDPOINT_URL));
  }

  public static Map<String, Object> snapshot(BootstrapConfig config, Consumer<String> logger) {
    try {
      final Map<String, Object> rawPayload = readStatusPayload(config);
      final SystemdStatusSnapshot statusSnapshot = toSystemdStatusSnapshot(rawPayload);

      final LinkedHashMap<String, Object> parsed =
          new LinkedHashMap<>(statusSnapshot.toPayloadMap());
      parsed.put("apiVersion", API_VERSION);
      parsed.put("kind", KIND);
      parsed.put("source", "systemd-adapter-http-probe");
      parsed.put("probeMode", "systemd-adapter-http");
      parsed.put("adapterEndpoint", ADAPTER_ENDPOINT_URL);
      parsed.put("status", "ok");
      parsed.putIfAbsent("capturedAt", Instant.now().toString());
      parsed.putIfAbsent("summary", "systemd adapter api probe captured runtime state");

      if (logger != null) {
        logger.accept("systemd adapter runtime summary: " + parsed.getOrDefault("summary", "n/a"));
      }
      return Map.copyOf(parsed);
    } catch (IllegalStateException ex) {
      return envelope(
          "execution-error",
          "systemd adapter api probe execution error: " + ex.getMessage(),
          Map.of("source", "systemd-adapter-http-probe", "adapterEndpoint", ADAPTER_ENDPOINT_URL));
    }
  }

  public static Map<String, Object> snapshotStandalone(BootstrapConfig config) {
    return snapshot(config, message -> System.out.println("[readiness] " + message));
  }

  private static Map<String, Object> readStatusPayload(BootstrapConfig config) {
    final CommandResult result =
        runCommandInInstance(config, buildEndpointFetchScript(ADAPTER_ENDPOINT_URL));

    if (looksLikeIncusHelp(result.stdout())) {
      throw new IllegalStateException(
          "received Incus CLI help while querying adapter endpoint " + ADAPTER_ENDPOINT_URL);
    }
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "failed querying adapter endpoint "
              + ADAPTER_ENDPOINT_URL
              + " ("
              + summarizeCommandFailure(result)
              + ")");
    }

    final String stdout = result.stdout() == null ? "" : result.stdout().trim();
    if (stdout.isBlank()) {
      throw new IllegalStateException(
          "empty response from adapter endpoint " + ADAPTER_ENDPOINT_URL);
    }

    final Map<String, Object> parsed = GSON.fromJson(stdout, MAP_TYPE);
    if (parsed == null || parsed.isEmpty()) {
      throw new IllegalStateException(
          "invalid/empty JSON payload from adapter endpoint " + ADAPTER_ENDPOINT_URL);
    }
    return Map.copyOf(parsed);
  }

  private static String buildEndpointFetchScript(String endpointUrl) {
    final String quotedEndpoint = shellQuote(endpointUrl);
    return "set -eu\n"
        + "if command -v curl >/dev/null 2>&1; then\n"
        + "  exec curl -fsSL --max-time 10 "
        + quotedEndpoint
        + "\n"
        + "fi\n"
        + "if command -v wget >/dev/null 2>&1; then\n"
        + "  exec wget -qO- --timeout=10 "
        + quotedEndpoint
        + "\n"
        + "fi\n"
        + "if command -v python3 >/dev/null 2>&1; then\n"
        + "  RK2LAB_ADAPTER_ENDPOINT="
        + quotedEndpoint
        + " exec python3 -c \"import os,urllib.request; print(urllib.request.urlopen(os.environ['RK2LAB_ADAPTER_ENDPOINT'], timeout=10).read().decode('utf-8'))\"\n"
        + "fi\n"
        + "echo 'missing HTTP client in instance (need curl, wget, or python3)' >&2\n"
        + "exit 127\n";
  }

  private static SystemdStatusSnapshot toSystemdStatusSnapshot(Map<String, Object> payload) {
    final String observedAt = normalizeString(payload.get("observedAt"), Instant.now().toString());
    final String mandatoryTarget =
        normalizeString(payload.get("mandatoryTarget"), "rke2lab.target");
    final String mandatoryTargetState =
        normalizeString(payload.get("mandatoryTargetState"), "unknown");
    final boolean mandatoryTargetHealthy = toBoolean(payload.get("mandatoryTargetHealthy"));
    final int pendingJobs = toInt(payload.get("pendingJobs"), 0);
    final Map<String, Integer> jobsByState = toIntMap(payload.get("jobsByState"));
    final int failedUnits = toInt(payload.get("failedUnits"), 0);
    final boolean runtimePrecheckReady = toBoolean(payload.get("runtimePrecheckReady"));
    final Map<String, String> connectionContext = toStringMap(payload.get("connectionContext"));
    final String summary = normalizeString(payload.get("summary"), "n/a");

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

  private static int toInt(Object value, int fallback) {
    if (value instanceof Number numberValue) {
      return Math.max(numberValue.intValue(), 0);
    }
    try {
      return Math.max(Integer.parseInt(String.valueOf(value).trim()), 0);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean toBoolean(Object value) {
    if (value instanceof Boolean boolValue) {
      return boolValue;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static Map<String, Integer> toIntMap(Object value) {
    if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
      return Map.of();
    }
    final LinkedHashMap<String, Integer> parsed = new LinkedHashMap<>();
    rawMap.forEach(
        (key, rawValue) -> {
          final String normalizedKey = normalizeString(key, "");
          if (normalizedKey.isBlank()) {
            return;
          }
          parsed.put(normalizedKey, toInt(rawValue, 0));
        });
    return Map.copyOf(parsed);
  }

  private static Map<String, String> toStringMap(Object value) {
    if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
      return Map.of();
    }
    final LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
    rawMap.forEach(
        (key, rawValue) -> {
          final String normalizedKey = normalizeString(key, "");
          if (normalizedKey.isBlank()) {
            return;
          }
          parsed.put(normalizedKey, normalizeString(rawValue, "unknown"));
        });
    return Map.copyOf(parsed);
  }

  private static String summarizeCommandFailure(CommandResult result) {
    final String stderrFirst = firstNonBlankLine(result.stderr(), "");
    if (!stderrFirst.isBlank()) {
      return stderrFirst;
    }
    final String stdoutFirst = firstNonBlankLine(result.stdout(), "");
    if (!stdoutFirst.isBlank()) {
      return stdoutFirst;
    }
    return "exit=" + result.exitCode();
  }

  private static String normalizeString(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    final String raw = value.toString().trim();
    if (raw.isBlank()) {
      return fallback;
    }
    return raw;
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
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
}
