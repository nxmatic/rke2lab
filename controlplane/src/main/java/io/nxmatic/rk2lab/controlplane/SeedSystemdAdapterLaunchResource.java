package io.nxmatic.rk2lab.controlplane;

import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Pulumi-side launch gate for the seed systemd adapter unit. */
public final class SeedSystemdAdapterLaunchResource {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);

  private SeedSystemdAdapterLaunchResource() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return Map.of(
        "source",
        "systemd-adapter-launch",
        "status",
        "deferred-preview",
        "unit",
        config.systemdAdapterUnitName(),
        "summary",
        "adapter launch gate deferred during preview",
        "capturedAt",
        Instant.now().toString());
  }

  public static Map<String, Object> ensureLaunched(
      BootstrapConfig config, Consumer<String> logger) {
    final String unitName = config.systemdAdapterUnitName();
    if (unitName == null || unitName.isBlank()) {
      throw new IllegalStateException(
          "Missing required configuration: systemd.adapter.unitName (BootstrapConfig.systemdAdapterUnitName)");
    }

    final CommandResult loadStateResult =
        runCommand(
            incusExec(
                config,
                "sh",
                "-lc",
                "systemctl show --property=LoadState --value " + shellQuote(unitName)));
    final String loadState = firstNonBlankLine(loadStateResult.stdout()).toLowerCase();

    if (loadStateResult.exitCode() != 0 || loadState.isBlank() || "not-found".equals(loadState)) {
      final String summary =
          "adapter unit not found: " + unitName + " (" + loadStateResult.summary() + ")";
      if (config.systemdAdapterLaunchRequired()) {
        throw new IllegalStateException(summary);
      }
      if (logger != null) {
        logger.accept(summary + " ; continuing because launchRequired=false");
      }
      return Map.of(
          "source",
          "systemd-adapter-launch",
          "status",
          "unit-missing",
          "unit",
          unitName,
          "summary",
          summary,
          "capturedAt",
          Instant.now().toString());
    }

    final CommandResult enableNowResult =
        runCommand(
            incusExec(config, "sh", "-lc", "systemctl enable --now " + shellQuote(unitName)));
    if (enableNowResult.exitCode() != 0) {
      throw new IllegalStateException(
          "Failed to launch systemd adapter unit " + unitName + ": " + enableNowResult.summary());
    }

    final CommandResult stateResult =
        runCommand(
            incusExec(
                config,
                "sh",
                "-lc",
                "systemctl show --property=ActiveState --property=SubState --value "
                    + shellQuote(unitName)));
    final List<String> stateLines = nonBlankLines(stateResult.stdout());
    final String activeState = stateLines.size() >= 1 ? stateLines.get(0) : "unknown";
    final String subState = stateLines.size() >= 2 ? stateLines.get(1) : "unknown";
    final boolean active = "active".equalsIgnoreCase(activeState);

    if (!active && config.systemdAdapterLaunchRequired()) {
      throw new IllegalStateException(
          "Adapter unit "
              + unitName
              + " is not active after launch (activeState="
              + activeState
              + ", subState="
              + subState
              + ")");
    }

    final String status = active ? "ok" : "not-active";
    final String summary =
        "unit=" + unitName + " activeState=" + activeState + " subState=" + subState;
    if (logger != null) {
      logger.accept("systemd adapter launch gate: " + summary);
    }

    return Map.of(
        "source",
        "systemd-adapter-launch",
        "status",
        status,
        "unit",
        unitName,
        "activeState",
        activeState,
        "subState",
        subState,
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
        "incus exec --project "
            + shellQuote(config.incusProject())
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

  private static List<String> nonBlankLines(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return value.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
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
