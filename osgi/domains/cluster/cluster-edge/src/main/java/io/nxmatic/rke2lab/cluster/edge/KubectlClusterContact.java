package io.nxmatic.rke2lab.cluster.edge;

import io.nxmatic.rke2lab.cluster.contract.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.contract.ControllerRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised cluster edge: the single door toward the live cluster's kube-apiserver. It
 * implements the cluster domain's {@link ClusterReadinessContact} seam by shelling {@code kubectl}
 * over a published kubeconfig — pure {@link ProcessBuilder}, embedding nothing (the dbus-systemd
 * edge needed three nested jars for ServiceLoader transport discovery; the kubectl mechanism needs
 * none).
 *
 * <p>Each method is a single, stateless contact: it asks {@code kubectl} ONE question about the
 * cluster as it is NOW and returns the raw boolean. There is no retry loop, no timeout policy, no
 * phase ordering, no doctor fact — the host owns all of that. An edge "makes the contact and
 * returns a raw fact"; it does not diagnose. SCR publishes it; the host resolves it from the
 * registry.
 */
@Component(service = ClusterReadinessContact.class)
public final class KubectlClusterContact implements ClusterReadinessContact {

  private static final Logger LOG = LoggerFactory.getLogger(KubectlClusterContact.class);

  /** Bounds each kubectl invocation so a single contact never blocks the host's wait loop. */
  private static final Duration CONTACT_TIMEOUT = Duration.ofSeconds(8);

  @Override
  public boolean isApiReady(Path kubeconfig) {
    final CommandResult readyz =
        runCommand(
            List.of(
                "kubectl",
                "--kubeconfig",
                kubeconfig.toString(),
                "--insecure-skip-tls-verify=true",
                "--request-timeout=5s",
                "get",
                "--raw=/readyz"),
            CONTACT_TIMEOUT);
    final boolean ready = readyz.exitCode() == 0 && readyz.stdout().trim().contains("ok");
    if (!ready) {
      LOG.debug("kube-apiserver /readyz not ok: {}", readyz.summary());
    }
    return ready;
  }

  @Override
  public boolean areControllersEffective(Path kubeconfig, List<ControllerRef> controllers) {
    for (ControllerRef controller : controllers) {
      if (!isControllerRolledOut(kubeconfig, controller)) {
        LOG.debug("controller not effective: {}", controller.ref());
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a single controller currently exists and is fully rolled out, read point-in-time:
   * {@code rollout status --watch=false} returns immediately with the present state (no blocking
   * wait — the host owns the retry loop). A non-zero exit covers both "not created yet" and "not
   * rolled out".
   */
  private boolean isControllerRolledOut(Path kubeconfig, ControllerRef controller) {
    final CommandResult rollout =
        runCommand(
            List.of(
                "kubectl",
                "--kubeconfig",
                kubeconfig.toString(),
                "--insecure-skip-tls-verify=true",
                "-n",
                controller.namespace(),
                "rollout",
                "status",
                controller.resourceRef(),
                "--watch=false"),
            CONTACT_TIMEOUT);
    return rollout.exitCode() == 0;
  }

  private static CommandResult runCommand(List<String> command, Duration timeout) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.environment().putIfAbsent("LANG", "C");

    try {
      final Process process = processBuilder.start();
      final boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        return new CommandResult(-1, "", "timed out after " + timeout);
      }

      final int exitCode = process.exitValue();
      final String stdout =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final String stderr =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      return new CommandResult(exitCode, stdout, stderr);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return new CommandResult(-1, "", "command interrupted");
    } catch (IOException ex) {
      return new CommandResult(-1, "", "failed to execute command: " + ex.getMessage());
    }
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
