package io.nxmatic.rk2lab.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Canonical runtime status probe backed by the systemd adapter endpoint. */
public final class SeedSystemdRuntimeStatusResource {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  private SeedSystemdRuntimeStatusResource() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return Map.of(
        "source",
        "systemd-adapter",
        "status",
        "deferred-preview",
        "summary",
        "runtime status deferred during preview; endpoint=" + config.systemdAdapterStatusEndpoint(),
        "capturedAt",
        Instant.now().toString());
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
        return Map.of(
            "source",
            "systemd-adapter",
            "status",
            "timeout",
            "summary",
            "adapter probe timed out after " + PROBE_TIMEOUT,
            "capturedAt",
            Instant.now().toString());
      }

      final String stdout =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      final String stderr =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();

      if (process.exitValue() != 0) {
        return Map.of(
            "source",
            "systemd-adapter",
            "status",
            "command-failed",
            "exitCode",
            process.exitValue(),
            "stderr",
            summarizeFirstLine(stderr),
            "summary",
            "adapter probe command failed",
            "capturedAt",
            Instant.now().toString());
      }

      final LinkedHashMap<String, Object> parsed =
          new LinkedHashMap<>(
              JSON_MAPPER.readValue(stdout, new TypeReference<Map<String, Object>>() {}));
      parsed.put("source", "systemd-adapter");
      parsed.put("status", "ok");
      parsed.put("endpoint", config.systemdAdapterStatusEndpoint().toString());
      parsed.putIfAbsent("capturedAt", Instant.now().toString());

      if (logger != null) {
        logger.accept("systemd adapter runtime summary: " + parsed.getOrDefault("summary", "n/a"));
      }
      return Map.copyOf(parsed);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return Map.of(
          "source",
          "systemd-adapter",
          "status",
          "interrupted",
          "summary",
          "adapter probe interrupted",
          "capturedAt",
          Instant.now().toString());
    } catch (IOException ex) {
      return Map.of(
          "source",
          "systemd-adapter",
          "status",
          "execution-error",
          "summary",
          "adapter probe execution error: " + ex.getMessage(),
          "capturedAt",
          Instant.now().toString());
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
        "incus exec --project "
            + shellQuote(config.incusProject())
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

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
