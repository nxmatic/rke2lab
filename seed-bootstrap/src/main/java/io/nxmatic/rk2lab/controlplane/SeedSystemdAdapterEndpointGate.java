package io.nxmatic.rk2lab.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
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

/** Pulumi-side gate that starts the adapter service and verifies endpoint reachability via REST. */
public final class SeedSystemdAdapterEndpointGate {

  private static final String API_VERSION = "rk2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterEndpointGateStatus";
  private static final String ADAPTER_SERVICE_UNIT = "rke2lab-systemd-adapter.service";
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  private SeedSystemdAdapterEndpointGate() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return envelope(
        "deferred-preview",
        "adapter endpoint gate deferred during preview",
        Map.of(
            "source",
            "systemd-adapter-endpoint-gate",
            "endpoint",
            config.systemdAdapterStatusEndpoint().toString()));
  }

  public static Map<String, Object> ensureReachable(
      BootstrapConfig config, Consumer<String> logger) {
    final CommandResult ensureStartedResult = ensureServiceStarted(config);
    if (ensureStartedResult.exitCode() != 0) {
      throw new IllegalStateException(
          "Adapter service failed to start: "
              + ADAPTER_SERVICE_UNIT
              + " ("
              + ensureStartedResult.summary()
              + ")");
    }

    final CommandResult probeResult =
        runCommand(
            incusExec(
                config,
                "sh",
                "-lc",
                "curl --silent --show-error --fail --max-time 5 "
                    + shellQuote(config.systemdAdapterStatusEndpoint().toString())));

    if (probeResult.exitCode() != 0) {
      throw new IllegalStateException(
          "Adapter endpoint unreachable: "
              + config.systemdAdapterStatusEndpoint()
              + " ("
              + probeResult.summary()
              + ")");
    }

    final String stdout = probeResult.stdout();
    if (!looksLikeJsonObject(stdout)) {
      return envelope(
          "non-json-response",
          "adapter endpoint returned non-JSON payload",
          Map.of(
              "source", "systemd-adapter-endpoint-gate",
              "endpoint", config.systemdAdapterStatusEndpoint().toString(),
              "payloadFirstLine", firstNonBlankLine(stdout)));
    }

    LinkedHashMap<String, Object> parsed;
    try {
      parsed =
          new LinkedHashMap<>(
              JSON_MAPPER.readValue(stdout, new TypeReference<Map<String, Object>>() {}));
    } catch (IOException ex) {
      return envelope(
          "non-json-response",
          "adapter endpoint JSON parse failed: " + ex.getMessage(),
          Map.of(
              "source", "systemd-adapter-endpoint-gate",
              "endpoint", config.systemdAdapterStatusEndpoint().toString(),
              "payloadFirstLine", firstNonBlankLine(stdout)));
    }

    final String summary =
        "endpoint="
            + config.systemdAdapterStatusEndpoint()
            + " status="
            + parsed.getOrDefault("status", "unknown");
    if (logger != null) {
      logger.accept("systemd adapter endpoint gate: " + summary);
    }

    return envelope(
        "ok",
        summary,
        Map.of(
            "source",
            "systemd-adapter-endpoint-gate",
            "serviceUnit",
            ADAPTER_SERVICE_UNIT,
            "endpoint",
            config.systemdAdapterStatusEndpoint().toString(),
            "adapterStatus",
            Map.copyOf(parsed)));
  }

  private static CommandResult ensureServiceStarted(BootstrapConfig config) {
    final String script =
        "set -eu\n"
            + "if [ -x /srv/host/systemd-scripts.d/rke2lab-systemd-adapter-install.sh ]; then\n"
            + "  /srv/host/systemd-scripts.d/rke2lab-systemd-adapter-install.sh\n"
            + "fi\n"
            + "systemctl daemon-reload\n"
            + "systemctl start "
            + shellQuote(ADAPTER_SERVICE_UNIT)
            + "\n"
            + "systemctl is-active "
            + shellQuote(ADAPTER_SERVICE_UNIT)
            + "\n";

    return runCommand(incusExec(config, "sh", "-lc", script));
  }

  private static CommandResult runCommand(List<String> command) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.environment().putIfAbsent("LANG", "C");
    try {
      final Process process = processBuilder.start();
      final boolean exited = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        return new CommandResult(-1, "", "timed out after " + COMMAND_TIMEOUT);
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

  private static List<String> incusExec(BootstrapConfig config, String... args) {
    final String remoteIncusCommand =
        "incus --project "
            + shellQuote(config.incusProject())
            + " exec "
            + " "
            + shellQuote(config.nodeName())
            + " -- "
            + joinShellQuoted(args);

    return List.of(
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "ConnectTimeout=10",
        config.imageBuilderHost(),
        "sh",
        "-lc",
        remoteIncusCommand);
  }

  private static String joinShellQuoted(String... values) {
    if (values == null || values.length == 0) {
      return "";
    }

    final ArrayList<String> quoted = new ArrayList<>(values.length);
    for (String value : values) {
      quoted.add(shellQuote(value == null ? "" : value));
    }
    return String.join(" ", quoted);
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  private static boolean looksLikeJsonObject(String value) {
    if (value == null) {
      return false;
    }
    final String trimmed = value.trim();
    return trimmed.startsWith("{") && trimmed.endsWith("}");
  }

  private static String firstNonBlankLine(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
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

  private record CommandResult(int exitCode, String stdout, String stderr) {
    private String summary() {
      if (exitCode == 0) {
        return "ok";
      }

      final String firstStderr = firstNonBlankLine(stderr);
      if (!firstStderr.isBlank()) {
        return firstStderr;
      }

      final String firstStdout = firstNonBlankLine(stdout);
      if (!firstStdout.isBlank()) {
        return firstStdout;
      }

      return "exit=" + exitCode;
    }
  }
}
