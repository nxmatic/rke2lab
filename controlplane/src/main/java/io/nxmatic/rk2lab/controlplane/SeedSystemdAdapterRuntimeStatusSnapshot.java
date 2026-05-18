package io.nxmatic.rk2lab.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Canonical runtime status snapshot probe backed by the systemd adapter endpoint. */
public final class SeedSystemdAdapterRuntimeStatusSnapshot {

  private static final String API_VERSION = "rk2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterRuntimeStatus";
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  private SeedSystemdAdapterRuntimeStatusSnapshot() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return envelope(
        "deferred-preview",
        "runtime status deferred during preview; endpoint=" + config.systemdAdapterStatusEndpoint(),
        Map.of(
            "source",
            "systemd-adapter",
            "endpoint",
            config.systemdAdapterStatusEndpoint().toString()));
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
            "adapter probe command failed",
            Map.of(
                "source", "systemd-adapter",
                "exitCode", process.exitValue(),
                "stderr", summarizeFirstLine(stderr),
                "stdout", summarizeFirstLine(stdout)));
      }

      if (!looksLikeJsonObject(stdout)) {
        return envelope(
            "non-json-response",
            "adapter probe returned non-JSON payload",
            Map.of(
                "source", "systemd-adapter",
                "endpoint", config.systemdAdapterStatusEndpoint().toString(),
                "payloadFirstLine", summarizeFirstLine(stdout)));
      }

      final LinkedHashMap<String, Object> parsed =
          new LinkedHashMap<>(
              JSON_MAPPER.readValue(stdout, new TypeReference<Map<String, Object>>() {}));
      final SystemdStatusSnapshot statusSnapshot = SystemdStatusSnapshot.fromPayloadMap(parsed);

      parsed.clear();
      parsed.putAll(statusSnapshot.toPayloadMap());
      parsed.put("apiVersion", API_VERSION);
      parsed.put("kind", KIND);
      parsed.put("source", "systemd-adapter");
      parsed.put("status", "ok");
      parsed.put("endpoint", config.systemdAdapterStatusEndpoint().toString());
      parsed.putIfAbsent("capturedAt", Instant.now().toString());
      parsed.putIfAbsent("summary", "adapter probe returned JSON payload");

      if (logger != null) {
        logger.accept("systemd adapter runtime summary: " + parsed.getOrDefault("summary", "n/a"));
      }
      return Map.copyOf(parsed);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return envelope(
          "interrupted", "adapter probe interrupted", Map.of("source", "systemd-adapter"));
    } catch (IOException ex) {
      return envelope(
          "execution-error",
          "adapter probe execution error: " + ex.getMessage(),
          Map.of("source", "systemd-adapter"));
    }
  }

  public static Map<String, Object> snapshotStandalone(BootstrapConfig config) {
    return snapshot(config, message -> System.out.println("[readiness] " + message));
  }

  private static String buildProbeCommand(BootstrapConfig config) {
    final String statusEndpoint = config.systemdAdapterStatusEndpoint().toString();
    final String script =
        "set -eu\n"
            + "curl --silent --show-error --fail --max-time 5 "
            + shellQuote(statusEndpoint);

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

  private static boolean looksLikeJsonObject(String value) {
    if (value == null) {
      return false;
    }
    final String trimmed = value.trim();
    return trimmed.startsWith("{") && trimmed.endsWith("}");
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
