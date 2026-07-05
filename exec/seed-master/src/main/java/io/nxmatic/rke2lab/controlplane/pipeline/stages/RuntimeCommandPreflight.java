package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Runtime preflight for required shell commands used by Stage A readiness/runtime probes. */
public final class RuntimeCommandPreflight {

  private static final long REMOTE_CHECK_TIMEOUT_SECONDS = 10;

  private RuntimeCommandPreflight() {
    // Utility class
  }

  public static void enforceRequiredCommands(List<String> commands, Consumer<String> logger) {
    final List<String> required = commands == null ? List.of() : commands;
    if (required.isEmpty()) {
      return;
    }

    final ArrayList<String> missing = new ArrayList<>();
    for (String command : required) {
      if (command == null || command.isBlank()) {
        continue;
      }
      if (!isCommandAvailable(command.trim())) {
        missing.add(command.trim());
      }
    }

    if (missing.isEmpty()) {
      if (logger != null) {
        logger.accept("runtime command preflight passed: " + String.join(", ", required));
      }
      return;
    }

    throw new IllegalStateException(
        "Missing required command(s) in PATH for Stage A runtime checks: "
            + String.join(", ", missing)
            + ". Run Pulumi in the activated Flox environment (inside the SSH session), e.g. "
            + "'flox activate -- bash -lc \"command -v ssh && command -v kubectl && pulumi up\"'.");
  }

  public static void enforceRemoteCommandAvailable(
      String sshHost, String remoteCommand, Consumer<String> logger) {
    final String host = sshHost == null ? "" : sshHost.trim();
    final String command = remoteCommand == null ? "" : remoteCommand.trim();

    if (host.isBlank()) {
      throw new IllegalStateException(
          "Missing ssh host for runtime command preflight. Configure rke2lab:image.builderHost.");
    }
    if (command.isBlank()) {
      return;
    }

    final List<String> checkCommand =
        List.of(
            "ssh",
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=8",
            host,
            "sh",
            "-lc",
            "command -v " + shellQuote(command) + " >/dev/null");

    final CommandResult result = runCommand(checkCommand, REMOTE_CHECK_TIMEOUT_SECONDS);
    if (result.exitCode() == 0) {
      if (logger != null) {
        logger.accept("runtime remote preflight passed: " + command + " available on " + host);
      }
      return;
    }

    throw new IllegalStateException(
        "Remote runtime preflight failed: command '"
            + command
            + "' is not available via ssh on host '"
            + host
            + "'. Ensure you're in the correct SSH/Flox environment and that the host has "
            + "Incus installed/available in PATH. Details: "
            + result.summary());
  }

  private static boolean isCommandAvailable(String command) {
    final Path directPath = Path.of(command);
    if (directPath.isAbsolute() || command.contains("/")) {
      return Files.isExecutable(directPath);
    }

    final String pathEnv = System.getenv("PATH");
    if (pathEnv == null || pathEnv.isBlank()) {
      return false;
    }

    for (String pathEntry : pathEnv.split(File.pathSeparator)) {
      if (pathEntry == null || pathEntry.isBlank()) {
        continue;
      }
      final Path candidate = Path.of(pathEntry, command);
      if (Files.isExecutable(candidate)) {
        return true;
      }
    }

    return false;
  }

  private static CommandResult runCommand(List<String> command, long timeoutSeconds) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.environment().putIfAbsent("LANG", "C");
    try {
      final Process process = processBuilder.start();
      final boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        return new CommandResult(-1, "", "timed out after " + timeoutSeconds + "s");
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

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
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

    private static String firstNonBlankLine(String value) {
      if (value == null || value.isBlank()) {
        return "";
      }
      return value.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
    }
  }
}
