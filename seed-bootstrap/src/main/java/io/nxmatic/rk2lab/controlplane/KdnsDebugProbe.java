package io.nxmatic.rk2lab.controlplane;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects kdns pods that are stuck in container creation (Pending/ContainerCreating state). Used by
 * {@link ClusterBootstrapReadinessVerifier} to surface an actionable hint via {@code SeedLog} (and
 * thus {@code pulumi up}) while the kdns rollout wait is in progress.
 *
 * <p>When {@code policy.debug.kdns.suspend=true}, kdns pods may be paused at startup waiting for a
 * debugger to attach. This probe detects such pods and provides operator guidance.
 *
 * <p>The probe is intentionally cheap and read-only: a single {@code kubectl get pods} call per
 * invocation. It does not call into containerd or the host directly.
 */
public final class KdnsDebugProbe {

  private static final Duration KUBECTL_TIMEOUT = Duration.ofSeconds(10);

  private KdnsDebugProbe() {}

  public static List<SuspendedPod> probe(Path kubeconfigPath) {
    final ProcessBuilder processBuilder =
        new ProcessBuilder(
            List.of(
                "kubectl",
                "--kubeconfig",
                kubeconfigPath.toString(),
                "--insecure-skip-tls-verify=true",
                "-n",
                "rke2lab-system",
                "get",
                "pods",
                "-l",
                "app.kubernetes.io/name=kdns",
                "-o",
                "jsonpath={range .items[?(@.status.phase==\"Pending\")]}"
                    + "{.metadata.name}{\"|\"}"
                    + "{.status.containerStatuses[*].state.waiting.reason}{\"|\"}"
                    + "{.status.initContainerStatuses[*].state.waiting.reason}{\"\\n\"}{end}"));
    processBuilder.environment().putIfAbsent("LANG", "C");

    try {
      final Process process = processBuilder.start();
      if (!process.waitFor(
          KUBECTL_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return List.of();
      }
      if (process.exitValue() != 0) {
        return List.of();
      }
      final String stdout =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return parse(stdout);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (IOException ex) {
      return List.of();
    }
  }

  private static List<SuspendedPod> parse(String stdout) {
    final List<SuspendedPod> result = new ArrayList<>();
    for (String line : stdout.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      final String[] parts = line.split("\\|", -1);
      if (parts.length < 2) {
        continue;
      }
      final String podName = parts[0].trim();
      if (podName.isEmpty()) {
        continue;
      }
      final String waitingReasons =
          (parts.length >= 2 ? parts[1] : "") + " " + (parts.length >= 3 ? parts[2] : "");
      // ContainerCreating indicates the pod is stuck in startup.
      // When debug suspend is enabled, this is expected — the pod is waiting for debugger attach.
      // When debug suspend is disabled, this indicates a genuine failure.
      if (waitingReasons.contains("ContainerCreating")
          || waitingReasons.contains("PodInitializing")
          || waitingReasons.contains("CreateContainerError")) {
        result.add(new SuspendedPod(podName, "rke2lab-system"));
      }
    }
    return result;
  }

  public record SuspendedPod(String podName, String namespace) {}
}
