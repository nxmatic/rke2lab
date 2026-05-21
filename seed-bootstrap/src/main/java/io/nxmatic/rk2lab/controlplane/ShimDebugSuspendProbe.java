package io.nxmatic.rk2lab.controlplane;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects kdns pods that are stuck in sandbox creation because their containerd-shim-flox wrapper
 * is parked at the {@code flox.dev/debug-suspend} gate. Used by {@link
 * ClusterBootstrapReadinessVerifier} to surface an actionable hint via {@code SeedLog} (and thus
 * {@code pulumi up}) while the kdns rollout wait is in progress.
 *
 * <p>The probe is intentionally cheap and read-only: a single {@code kubectl get pods} call per
 * invocation. It does not call into containerd or the host directly — that surface is reserved for
 * the operator-facing {@code rke2lab-shim-dlv} helper on the master VM.
 */
public final class ShimDebugSuspendProbe {

  private static final Duration KUBECTL_TIMEOUT = Duration.ofSeconds(10);

  private ShimDebugSuspendProbe() {}

  public static List<SuspendedShim> probe(Path kubeconfigPath) {
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

  private static List<SuspendedShim> parse(String stdout) {
    final List<SuspendedShim> result = new ArrayList<>();
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
      // ContainerCreating is the kubelet-side symptom of a sandbox-stuck pod.
      // Either the wrapper is parked at the suspend gate, or the wrapper is genuinely
      // failing — in both cases the operator wants the actionable hint.
      if (waitingReasons.contains("ContainerCreating")
          || waitingReasons.contains("PodInitializing")
          || waitingReasons.contains("CreateContainerError")) {
        result.add(new SuspendedShim(podName, "rke2lab-system"));
      }
    }
    return result;
  }

  public record SuspendedShim(String podName, String namespace) {}
}
