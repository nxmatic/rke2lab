package io.nxmatic.rke2lab.incus.edge;

import io.nxmatic.rke2lab.incus.contract.IncusExecRequest;
import io.nxmatic.rke2lab.incus.contract.IncusInstanceContact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised incus edge: tests instance reachability by running a trivial command on it over
 * {@code ssh … incus exec}. The single door toward this one external contact — the ProcessBuilder
 * mechanism formerly inlined in the host {@code SeedSystemdAdapterEndpointGate}
 * ({@code @Transitional(to="incus-edge")}). {@code ProcessBuilder} is playable, so this edge lives
 * in the OSGi world; SCR publishes it and the host gate composes it from the registry.
 *
 * <p><b>Runtime dependency:</b> {@code ssh} on {@code PATH} and key-based access to the Incus host.
 */
@Component(service = IncusInstanceContact.class)
public final class ProcessBuilderIncusInstanceContact implements IncusInstanceContact {

  private static final Logger LOG =
      LoggerFactory.getLogger(ProcessBuilderIncusInstanceContact.class);
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);

  @Override
  public Optional<String> isReachable(IncusExecRequest request) {
    final CommandResult result = runCommand(incusExec(request, "true"));
    return result.exitCode() == 0 ? Optional.empty() : Optional.of(result.summary());
  }

  private static List<String> incusExec(IncusExecRequest request, String... args) {
    // ssh joins post-destination argv with spaces and re-parses on the remote side, so a multi-line
    // script passed as a separate `sh -lc <script>` argv entry would be split on whitespace. Build
    // the entire remote command as a single shell-quoted string and hand it to ssh as one argument.
    final String remoteIncusCommand =
        "incus --project "
            + shellQuote(request.incusProject())
            + " exec "
            + shellQuote(request.nodeName())
            + " -- "
            + joinShellQuoted(args);

    return List.of(
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "ConnectTimeout=10",
        request.sshHost(),
        remoteIncusCommand);
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
