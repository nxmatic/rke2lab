package io.nxmatic.rke2lab.controlplane.systemd;

import io.nxmatic.rke2lab.controlplane.bdd.Dossier;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Pulumi-side gate that waits for the dbus-on-TCP probe to report ok. */
public final class SeedSystemdAdapterEndpointGate {

  private static final String API_VERSION = "rke2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterEndpointGateStatus";
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
  // Adaptive retry intervals based on bootstrap phase
  private static final Duration RUNTIME_PROBE_RETRY_INTERVAL_EARLY = Duration.ofSeconds(15);
  private static final Duration RUNTIME_PROBE_RETRY_INTERVAL_MID = Duration.ofSeconds(8);
  private static final Duration RUNTIME_PROBE_RETRY_INTERVAL_LATE = Duration.ofSeconds(3);
  private static final Duration RUNTIME_PROBE_RETRY_INTERVAL_FINAL = Duration.ofSeconds(2);
  private static final long PHASE_EARLY_CUTOFF_SECONDS = 90; // Image building / first boot
  private static final long PHASE_MID_CUTOFF_SECONDS = 150; // Systemd initialization
  private static final long PHASE_LATE_CUTOFF_SECONDS = 210; // Service convergence
  private static final Duration PROGRESS_LOG_INTERVAL = Duration.ofSeconds(15);
  private static final Duration INSTANCE_READY_RETRY_INTERVAL = Duration.ofSeconds(2);

  private SeedSystemdAdapterEndpointGate() {
    // Utility class
  }

  public static Dossier deferredPreview(BootstrapConfig config) {
    return Dossier.of(
        "deferred-preview",
        Optional.empty(),
        "adapter endpoint gate deferred during preview",
        details(
            Map.of(
                "source",
                "systemd-adapter-endpoint-gate",
                "probeMode",
                "systemd-adapter-runtime")));
  }

  public static Dossier ensureReachable(BootstrapConfig config, Consumer<String> logger) {
    waitForInstanceReachable(config, logger);

    final Map<String, Object> runtimeSnapshot = waitForRuntimeProbe(config, logger);
    final String runtimeStatus = String.valueOf(runtimeSnapshot.getOrDefault("status", "unknown"));

    final String summary =
        "dbusEndpoint="
            + config.systemdAdapterDbusHost()
            + ":"
            + config.systemdAdapterDbusPort()
            + " status="
            + runtimeStatus
            + " probeMode=systemd-adapter-runtime";
    if (logger != null) {
      logger.accept("systemd adapter endpoint gate: " + summary);
    }

    return Dossier.ok(
        summary,
        details(
            Map.of(
                "source",
                "systemd-adapter-endpoint-gate",
                "probeMode",
                "systemd-adapter-runtime",
                "adapterStatus",
                Map.copyOf(runtimeSnapshot))));
  }

  private static Map<String, Object> waitForRuntimeProbe(
      BootstrapConfig config, Consumer<String> logger) {
    final Duration tolerance = config.readinessTimeout();
    final long startedAt = System.nanoTime();
    final long deadlineNanos = startedAt + tolerance.toNanos();
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
                + tolerance
                + ")");
        nextProgressLogAt = now + PROGRESS_LOG_INTERVAL.toNanos();
      }

      // Adaptive retry interval: slower during early boot, faster as we approach readiness
      final long elapsedSeconds = Duration.ofNanos(now - startedAt).toSeconds();
      final Duration retryInterval = computeRuntimeProbeRetryInterval(elapsedSeconds);
      sleep(retryInterval);
    }

    final String lastSummary = String.valueOf(lastSnapshot.getOrDefault("summary", "unknown"));
    final String lastStatus = String.valueOf(lastSnapshot.getOrDefault("status", "unknown"));
    throw new IllegalStateException(
        "Adapter runtime probe failed at "
            + config.systemdAdapterDbusHost()
            + ":"
            + config.systemdAdapterDbusPort()
            + " after "
            + tolerance
            + " (last status="
            + lastStatus
            + ", summary="
            + lastSummary
            + ")");
  }

  /**
   * Compute adaptive retry interval based on bootstrap phase. Early boot (image building, first
   * boot): slower checks to reduce CPU load. Later phases (service convergence): faster checks for
   * responsiveness.
   */
  private static Duration computeRuntimeProbeRetryInterval(long elapsedSeconds) {
    if (elapsedSeconds < PHASE_EARLY_CUTOFF_SECONDS) {
      // Phase 1: Image building / first boot - check every 15s
      return RUNTIME_PROBE_RETRY_INTERVAL_EARLY;
    } else if (elapsedSeconds < PHASE_MID_CUTOFF_SECONDS) {
      // Phase 2: Systemd initialization - check every 8s
      return RUNTIME_PROBE_RETRY_INTERVAL_MID;
    } else if (elapsedSeconds < PHASE_LATE_CUTOFF_SECONDS) {
      // Phase 3: Service convergence - check every 3s
      return RUNTIME_PROBE_RETRY_INTERVAL_LATE;
    } else {
      // Phase 4: Final readiness - check every 2s
      return RUNTIME_PROBE_RETRY_INTERVAL_FINAL;
    }
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
    final Duration tolerance = config.readinessTimeout();
    final long startedAt = System.nanoTime();
    final long deadlineNanos = startedAt + tolerance.toNanos();
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
                + tolerance
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
            + tolerance
            + " (last result: "
            + (lastResult == null ? "<no attempts>" : lastResult.summary())
            + ")");
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

  /**
   * The gate's resource-identity metadata ({@code apiVersion}/{@code kind}) merged ahead of the
   * call-site details, forming the {@link Dossier}'s details map. {@code status}/{@code summary}
   * are the dossier's own fields and are re-added by {@link Dossier#toOutputMap()}, so the flat
   * output keys are unchanged from the former envelope.
   */
  private static Map<String, Object> details(Map<String, Object> callerDetails) {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put("apiVersion", API_VERSION);
    map.put("kind", KIND);
    if (callerDetails != null) {
      map.putAll(callerDetails);
    }
    return map;
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
