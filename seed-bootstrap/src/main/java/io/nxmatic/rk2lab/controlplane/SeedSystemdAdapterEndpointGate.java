package io.nxmatic.rk2lab.controlplane;

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
 * Pulumi-side gate that starts the adapter service and verifies host-local runtime probe output.
 */
public final class SeedSystemdAdapterEndpointGate {

  private static final String API_VERSION = "rk2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterEndpointGateStatus";
  private static final String ADAPTER_SERVICE_UNIT = "rke2lab-systemd-adapter.service";
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration RUNTIME_PROBE_TOLERANCE = Duration.ofMinutes(4);
  private static final Duration RUNTIME_PROBE_RETRY_INTERVAL = Duration.ofSeconds(2);
  private static final Duration PROGRESS_LOG_INTERVAL = Duration.ofSeconds(15);
  private static final Duration INSTANCE_READY_TOLERANCE = Duration.ofMinutes(4);
  private static final Duration INSTANCE_READY_RETRY_INTERVAL = Duration.ofSeconds(2);

  private SeedSystemdAdapterEndpointGate() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return envelope(
        "deferred-preview",
        "adapter service gate deferred during preview",
        Map.of(
            "source",
            "systemd-adapter-endpoint-gate",
            "serviceUnit",
            ADAPTER_SERVICE_UNIT,
            "probeMode",
            "systemd-adapter-runtime"));
  }

  public static Map<String, Object> ensureReachable(
      BootstrapConfig config, Consumer<String> logger) {
    waitForInstanceReachable(config, logger);
    final CommandResult ensureStartedResult = ensureServiceStarted(config);
    if (ensureStartedResult.exitCode() != 0) {
      throw new IllegalStateException(
          "Adapter service failed to start: "
              + ADAPTER_SERVICE_UNIT
              + " ("
              + ensureStartedResult.summary()
              + ")");
    }

    final Map<String, Object> runtimeSnapshot = waitForRuntimeProbe(config, logger);
    final String runtimeStatus = String.valueOf(runtimeSnapshot.getOrDefault("status", "unknown"));

    final String summary =
        "serviceUnit="
            + ADAPTER_SERVICE_UNIT
            + " status="
            + runtimeStatus
            + " probeMode=systemd-adapter-runtime";
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
            "probeMode",
            "systemd-adapter-runtime",
            "adapterStatus",
            Map.copyOf(runtimeSnapshot)));
  }

  private static Map<String, Object> waitForRuntimeProbe(
      BootstrapConfig config, Consumer<String> logger) {
    final long startedAt = System.nanoTime();
    final long deadlineNanos = startedAt + RUNTIME_PROBE_TOLERANCE.toNanos();
    long nextProgressLogAt = startedAt;

    Map<String, Object> lastSnapshot = Map.of();
    while (System.nanoTime() < deadlineNanos) {
      final Map<String, Object> runtimeSnapshot =
          SeedSystemdAdapterRuntimeStatusSnapshot.snapshot(config, null);
      final String runtimeStatus =
          String.valueOf(runtimeSnapshot.getOrDefault("status", "unknown")).trim();

      if ("ok".equalsIgnoreCase(runtimeStatus)) {
        return runtimeSnapshot;
      }

      lastSnapshot = runtimeSnapshot;

      final long now = System.nanoTime();
      if (logger != null && now >= nextProgressLogAt) {
        logger.accept(
            "systemd adapter runtime probe not ready yet; status="
                + runtimeStatus
                + ", summary="
                + runtimeSnapshot.getOrDefault("summary", "n/a")
                + " (retrying for up to "
                + RUNTIME_PROBE_TOLERANCE
                + ")");
        nextProgressLogAt = now + PROGRESS_LOG_INTERVAL.toNanos();
      }

      sleep(RUNTIME_PROBE_RETRY_INTERVAL);
    }

    final String lastSummary = String.valueOf(lastSnapshot.getOrDefault("summary", "unknown"));
    final String lastStatus = String.valueOf(lastSnapshot.getOrDefault("status", "unknown"));
    throw new IllegalStateException(
        "Adapter runtime probe failed for service "
            + ADAPTER_SERVICE_UNIT
            + " after "
            + RUNTIME_PROBE_TOLERANCE
            + " (last status="
            + lastStatus
            + ", summary="
            + lastSummary
            + ")");
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  // Wait for the seed instance to be reachable via `incus exec`. Pulumi
  // registers the instance resource concurrently with this Main-driven gate
  // call, so on first apply the instance may not yet exist when ensureReachable
  // runs. Retry the cheapest no-op probe until incus exec succeeds.
  private static void waitForInstanceReachable(BootstrapConfig config, Consumer<String> logger) {
    final long startedAt = System.nanoTime();
    final long deadlineNanos = startedAt + INSTANCE_READY_TOLERANCE.toNanos();
    long nextProgressLogAt = startedAt;
    CommandResult lastResult = null;
    while (System.nanoTime() < deadlineNanos) {
      lastResult = runCommand(incusExec(config, "true"));
      if (lastResult.exitCode() == 0) {
        return;
      }
      final long now = System.nanoTime();
      if (logger != null && now >= nextProgressLogAt) {
        logger.accept(
            "instance "
                + config.nodeName()
                + " in project "
                + config.incusProject()
                + " not reachable yet via incus exec; "
                + lastResult.summary()
                + " (retrying for up to "
                + INSTANCE_READY_TOLERANCE
                + ")");
        nextProgressLogAt = now + PROGRESS_LOG_INTERVAL.toNanos();
      }
      sleep(INSTANCE_READY_RETRY_INTERVAL);
    }
    throw new IllegalStateException(
        "Instance "
            + config.nodeName()
            + " in project "
            + config.incusProject()
            + " did not become reachable via incus exec within "
            + INSTANCE_READY_TOLERANCE
            + " (last result: "
            + (lastResult == null ? "<no attempts>" : lastResult.summary())
            + ")");
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
    // ssh joins post-destination argv with spaces and re-parses on the remote
    // side, so a multi-line script passed as a separate `sh -lc <script>` argv
    // entry would be split on whitespace. Build the entire remote command as a
    // single shell-quoted string and hand it to ssh as one argument.
    final String remoteIncusCommand =
        "incus --project "
            + shellQuote(config.incusProject())
            + " exec "
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
