package io.nxmatic.rk2lab.controlplane;

import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic bootstrap readiness verification contract.
 *
 * <p>Checks in canonical order:
 *
 * <ol>
 *   <li>kubeconfig is published at expected host path.
 *   <li>Kubernetes API responds via kubeconfig and reports readyz=ok.
 *   <li>Required controllers (derived from bootstrap policy) are present and rolled out.
 * </ol>
 */
public final class ClusterBootstrapReadinessVerifier {

  private static final Duration KUBECONFIG_WAIT_TIMEOUT = Duration.ofMinutes(10);

  private static final Duration API_READY_TIMEOUT = Duration.ofMinutes(10);

  private static final Duration CONTROLLER_WAIT_TIMEOUT = Duration.ofMinutes(10);

  private static final Duration RETRY_INTERVAL = Duration.ofSeconds(2);

  private ClusterBootstrapReadinessVerifier() {
    // Utility class
  }

  public static VerificationResult verify(BootstrapConfig config, ControlplanePolicy policy) {
    final Path kubeconfigPath = config.kubeconfigRef().toAbsolutePath().normalize();

    if (!waitForKubeconfigPublished(kubeconfigPath)) {
      return VerificationResult.failed(
          false,
          false,
          false,
          "kubeconfig was not published within timeout at: " + kubeconfigPath,
          requiredControllerRefs(policy));
    }

    if (!waitForApiReady(kubeconfigPath)) {
      return VerificationResult.failed(
          true,
          false,
          false,
          "kubernetes API did not report readyz=ok within timeout using kubeconfig: "
              + kubeconfigPath,
          requiredControllerRefs(policy));
    }

    final ControllerVerification controllers = verifyRequiredControllers(kubeconfigPath, policy);
    if (!controllers.ready()) {
      return VerificationResult.failed(
          true,
          true,
          false,
          "required bootstrap controllers are not effective: " + controllers.detail(),
          controllers.requiredControllerRefs());
    }

    return VerificationResult.ready(controllers.requiredControllerRefs());
  }

  private static boolean waitForKubeconfigPublished(Path kubeconfigPath) {
    final long deadlineNanos = System.nanoTime() + KUBECONFIG_WAIT_TIMEOUT.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      try {
        if (Files.exists(kubeconfigPath)
            && Files.isRegularFile(kubeconfigPath)
            && Files.size(kubeconfigPath) > 0) {
          final String content = Files.readString(kubeconfigPath, StandardCharsets.UTF_8);
          if (content.contains("apiVersion:") && content.contains("clusters:")) {
            return true;
          }
        }
      } catch (IOException ignored) {
        // keep retrying until timeout
      }

      sleep(RETRY_INTERVAL);
    }
    return false;
  }

  private static boolean waitForApiReady(Path kubeconfigPath) {
    final long deadlineNanos = System.nanoTime() + API_READY_TIMEOUT.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      final CommandResult readyzResult =
          runCommand(
              List.of(
                  "kubectl",
                  "--kubeconfig",
                  kubeconfigPath.toString(),
                  "--request-timeout=5s",
                  "get",
                  "--raw=/readyz"),
              Duration.ofSeconds(8));
      if (readyzResult.exitCode() == 0 && readyzResult.stdout().trim().contains("ok")) {
        return true;
      }
      sleep(RETRY_INTERVAL);
    }
    return false;
  }

  private static ControllerVerification verifyRequiredControllers(
      Path kubeconfigPath, ControlplanePolicy policy) {
    final List<ControllerRef> requiredControllers = requiredControllers(policy);
    if (requiredControllers.isEmpty()) {
      return new ControllerVerification(true, "no required controllers", List.of());
    }

    for (ControllerRef controllerRef : requiredControllers) {
      final String resourceRef = controllerRef.kind() + "/" + controllerRef.name();
      final CommandResult createdResult =
          runCommand(
              List.of(
                  "kubectl",
                  "--kubeconfig",
                  kubeconfigPath.toString(),
                  "-n",
                  controllerRef.namespace(),
                  "wait",
                  "--for=create",
                  resourceRef,
                  "--timeout=" + CONTROLLER_WAIT_TIMEOUT.toSeconds() + "s"),
              CONTROLLER_WAIT_TIMEOUT.plusSeconds(5));
      if (createdResult.exitCode() != 0) {
        return new ControllerVerification(
            false,
            "failed waiting for resource create "
                + controllerRef.ref()
                + " ("
                + createdResult.summary()
                + ")",
            requiredControllerRefs(policy));
      }

      final CommandResult rolloutResult =
          runCommand(
              List.of(
                  "kubectl",
                  "--kubeconfig",
                  kubeconfigPath.toString(),
                  "-n",
                  controllerRef.namespace(),
                  "rollout",
                  "status",
                  resourceRef,
                  "--timeout=" + CONTROLLER_WAIT_TIMEOUT.toSeconds() + "s"),
              CONTROLLER_WAIT_TIMEOUT.plusSeconds(5));
      if (rolloutResult.exitCode() != 0) {
        return new ControllerVerification(
            false,
            "failed rollout status for "
                + controllerRef.ref()
                + " ("
                + rolloutResult.summary()
                + ")",
            requiredControllerRefs(policy));
      }
    }

    return new ControllerVerification(
        true, "all required controllers rolled out", requiredControllerRefs(policy));
  }

  private static List<ControllerRef> requiredControllers(ControlplanePolicy policy) {
    final ArrayList<ControllerRef> refs = new ArrayList<>();

    if (policy.manifestLink().highAvailabilityEnabled()) {
      refs.add(new ControllerRef("daemonset", "kube-vip-ds", "kube-vip"));
    }

    if (policy.manifestLink().networkingEnabled()) {
      refs.add(new ControllerRef("deployment", "cilium-operator", "kube-system"));
      refs.add(new ControllerRef("deployment", "kdns", "rke2lab-system"));
    }

    if (policy.manifestLink().replicationEnabled()) {
      refs.add(new ControllerRef("deployment", "kubernetes-replicator", "kube-system"));
    }

    if (policy.manifestLink().storageEnabled()) {
      refs.add(new ControllerRef("deployment", "openebs-zfs-zfs-localpv-controller", "openebs"));
      refs.add(new ControllerRef("daemonset", "openebs-zfs-zfs-localpv-node", "openebs"));
    }

    if (policy.manifestLink().meshEnabled()) {
      refs.add(new ControllerRef("deployment", "headscale", "mesh-system"));
      refs.add(new ControllerRef("deployment", "headscale-gateway", "mesh-system"));
      refs.add(new ControllerRef("daemonset", "headscale-client", "mesh-system"));
      refs.add(new ControllerRef("deployment", "headplane", "mesh-system"));
    }

    return List.copyOf(refs);
  }

  private static List<String> requiredControllerRefs(ControlplanePolicy policy) {
    return requiredControllers(policy).stream().map(ControllerRef::ref).toList();
  }

  private static CommandResult runCommand(List<String> command, Duration timeout) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.environment().putIfAbsent("LANG", "C");

    try {
      final Process process = processBuilder.start();
      final boolean exited =
          process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
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

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  public record VerificationResult(
      boolean kubeconfigPublished,
      boolean apiReady,
      boolean controllersEffective,
      boolean handoffReady,
      String bootstrapStatus,
      String summary,
      List<String> requiredControllerRefs) {

    private static VerificationResult ready(List<String> requiredControllerRefs) {
      return new VerificationResult(
          true,
          true,
          true,
          true,
          "Ready",
          "cluster readiness verified (kubeconfig published, API ready, required controllers effective)",
          List.copyOf(requiredControllerRefs));
    }

    private static VerificationResult failed(
        boolean kubeconfigPublished,
        boolean apiReady,
        boolean controllersEffective,
        String summary,
        List<String> requiredControllerRefs) {
      return new VerificationResult(
          kubeconfigPublished,
          apiReady,
          controllersEffective,
          false,
          "Failed",
          summary,
          List.copyOf(requiredControllerRefs));
    }

    public Map<String, Object> asOutputs() {
      final LinkedHashMap<String, Object> outputs = new LinkedHashMap<>();
      outputs.put("clusterKubeconfigPublished", kubeconfigPublished);
      outputs.put("clusterApiReady", apiReady);
      outputs.put("clusterControllersEffective", controllersEffective);
      outputs.put("clusterRequiredControllers", requiredControllerRefs);
      outputs.put("clusterReadinessSummary", summary);
      return outputs;
    }
  }

  private record ControllerRef(String kind, String name, String namespace) {
    private String ref() {
      return kind + "/" + name + "@" + namespace;
    }
  }

  private record ControllerVerification(
      boolean ready, String detail, List<String> requiredControllerRefs) {}

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
