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

/**
 * Pulumi-side gate that verifies adapter endpoint reachability via REST, without systemd control.
 */
public final class SeedSystemdAdapterEndpointGate {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  private SeedSystemdAdapterEndpointGate() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return Map.of(
        "source",
        "systemd-adapter-endpoint-gate",
        "status",
        "deferred-preview",
        "endpoint",
        config.systemdAdapterStatusEndpoint().toString(),
        "summary",
        "adapter endpoint gate deferred during preview",
        "capturedAt",
        Instant.now().toString());
  }

  public static Map<String, Object> ensureReachable(
      BootstrapConfig config, Consumer<String> logger) {
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

    LinkedHashMap<String, Object> parsed;
    try {
      parsed =
          new LinkedHashMap<>(
              JSON_MAPPER.readValue(
                  probeResult.stdout(), new TypeReference<Map<String, Object>>() {}));
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Adapter endpoint returned non-JSON payload at "
              + config.systemdAdapterStatusEndpoint()
              + ": "
              + ex.getMessage());
    }

    final String summary =
        "endpoint="
            + config.systemdAdapterStatusEndpoint()
            + " status="
            + parsed.getOrDefault("status", "unknown");
    if (logger != null) {
      logger.accept("systemd adapter endpoint gate: " + summary);
    }

    return Map.of(
        "source",
        "systemd-adapter-endpoint-gate",
        "status",
        "ok",
        "endpoint",
        config.systemdAdapterStatusEndpoint().toString(),
        "summary",
        summary,
        "capturedAt",
        Instant.now().toString());
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

  private static String firstNonBlankLine(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
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
