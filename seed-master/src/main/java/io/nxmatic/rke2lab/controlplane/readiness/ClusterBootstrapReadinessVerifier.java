package io.nxmatic.rke2lab.controlplane.readiness;

import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.SeedNodeBootstrapWatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

  private static final Duration RETRY_INTERVAL = Duration.ofSeconds(2);

  private static final Duration LOG_PROGRESS_INTERVAL = Duration.ofSeconds(30);

  private static final Consumer<String> DEFAULT_LOGGER =
      message -> SeedLog.info("readiness", message);

  private static final ThreadLocal<Consumer<String>> ACTIVE_LOGGER =
      ThreadLocal.withInitial(() -> DEFAULT_LOGGER);

  private ClusterBootstrapReadinessVerifier() {
    // Utility class
  }

  /**
   * One readiness phase checked in isolation — the per-phase seam the BDD checkpoint plays against
   * (a production {@code ClusterReadinessProbe} maps this to an {@code Observation}). Reuses the
   * same private waiters as {@link #verify}, so the live logic is identical; the verifier stays
   * free of any {@code bdd} types (no package cycle). {@code logger} is applied for the call.
   */
  public static PhaseOutcome checkKubeconfigPublished(
      BootstrapConfig config, Consumer<String> logger) {
    return runPhase(
        logger,
        () -> {
          // Phase-0 gate (preserved from the former verify()): the seed node's systemd/bootstrap
          // preconditions must converge before the kubeconfig can appear. Folded into the first
          // phase so the live ordering is unchanged.
          final Duration timeout = config.readinessTimeout();
          if (!SeedNodeBootstrapWatcher.waitForBootstrapPreconditions(
              config,
              new SeedNodeBootstrapWatcher.WaitConfig(
                  timeout, RETRY_INTERVAL, LOG_PROGRESS_INTERVAL),
              ClusterBootstrapReadinessVerifier::logInfo)) {
            return new PhaseOutcome(
                false,
                "seed node bootstrap gate did not converge (systemd jobs/services + rke2"
                    + " preconditions) for "
                    + config.nodeName()
                    + " in project "
                    + config.incusProject());
          }
          final Path kubeconfigPath = config.kubeconfigRef().toAbsolutePath().normalize();
          final boolean ok = waitForKubeconfigPublished(kubeconfigPath, timeout);
          return new PhaseOutcome(
              ok,
              ok
                  ? "kubeconfig published at " + kubeconfigPath
                  : "kubeconfig not published within timeout at " + kubeconfigPath);
        });
  }

  public static PhaseOutcome checkApiReady(BootstrapConfig config, Consumer<String> logger) {
    return runPhase(
        logger,
        () -> {
          final Path kubeconfigPath = config.kubeconfigRef().toAbsolutePath().normalize();
          final boolean ok = waitForApiReady(kubeconfigPath, config.readinessTimeout());
          return new PhaseOutcome(
              ok,
              ok ? "kubernetes API reports readyz=ok" : "kubernetes API did not report readyz=ok");
        });
  }

  public static PhaseOutcome checkControllersEffective(
      BootstrapConfig config, ControlplanePolicy policy, Consumer<String> logger) {
    return runPhase(
        logger,
        () -> {
          final Path kubeconfigPath = config.kubeconfigRef().toAbsolutePath().normalize();
          final ControllerVerification controllers =
              verifyRequiredControllers(kubeconfigPath, policy, config.readinessTimeout());
          return new PhaseOutcome(controllers.ready(), controllers.detail());
        });
  }

  private static PhaseOutcome runPhase(
      Consumer<String> logger, java.util.function.Supplier<PhaseOutcome> phase) {
    final Consumer<String> previous = ACTIVE_LOGGER.get();
    ACTIVE_LOGGER.set(logger == null ? DEFAULT_LOGGER : logger);
    try {
      return phase.get();
    } finally {
      ACTIVE_LOGGER.set(previous);
    }
  }

  /** The required-controller refs for a policy — exposed for the BDD output projection. */
  public static List<String> controllerRefs(ControlplanePolicy policy) {
    return requiredControllerRefs(policy);
  }

  /** Outcome of a single readiness phase: passed, plus a human detail line. */
  public record PhaseOutcome(boolean ok, String detail) {}

  public static VerificationResult skipped(ControlplanePolicy policy) {
    logInfo("readiness check disabled by configuration (rke2lab:readiness.enabled=false)");
    return VerificationResult.skipped(requiredControllerRefs(policy));
  }

  public static VerificationResult skipped(ControlplanePolicy policy, Consumer<String> logger) {
    final Consumer<String> previous = ACTIVE_LOGGER.get();
    ACTIVE_LOGGER.set(logger == null ? DEFAULT_LOGGER : logger);
    try {
      return skipped(policy);
    } finally {
      ACTIVE_LOGGER.set(previous);
    }
  }

  public static VerificationResult deferredPreview(ControlplanePolicy policy) {
    logInfo("readiness check deferred during preview; live checks run during apply");
    return VerificationResult.deferredPreview(requiredControllerRefs(policy));
  }

  public static VerificationResult deferredPreview(
      ControlplanePolicy policy, Consumer<String> logger) {
    final Consumer<String> previous = ACTIVE_LOGGER.get();
    ACTIVE_LOGGER.set(logger == null ? DEFAULT_LOGGER : logger);
    try {
      return deferredPreview(policy);
    } finally {
      ACTIVE_LOGGER.set(previous);
    }
  }

  /**
   * Projection factories the BDD checkpoint uses to build a {@link VerificationResult} from the
   * per-phase outcomes it played — the verifier stays the owner of result construction, so the
   * output contract (handoffReady → nextStep, bootstrapStatus, the output keys) is produced in one
   * place whether reached via {@link #verify} or via the scenario.
   */
  public static VerificationResult ready(ControlplanePolicy policy) {
    return VerificationResult.ready(requiredControllerRefs(policy));
  }

  public static VerificationResult failed(
      boolean kubeconfigPublished,
      boolean apiReady,
      boolean controllersEffective,
      String summary,
      ControlplanePolicy policy) {
    return VerificationResult.failed(
        kubeconfigPublished,
        apiReady,
        controllersEffective,
        summary,
        requiredControllerRefs(policy));
  }

  private static boolean waitForKubeconfigPublished(Path kubeconfigPath, Duration timeout) {
    logInfo("waiting for kubeconfig publication...");
    final long startedAt = System.nanoTime();
    long nextProgressLogAt = startedAt + LOG_PROGRESS_INTERVAL.toNanos();
    final long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      try {
        if (Files.exists(kubeconfigPath)
            && Files.isRegularFile(kubeconfigPath)
            && Files.size(kubeconfigPath) > 0) {
          final String content = Files.readString(kubeconfigPath, StandardCharsets.UTF_8);
          if (content.contains("apiVersion:") && content.contains("clusters:")) {
            logInfo("kubeconfig is published after " + elapsedSince(startedAt));
            return true;
          }
        }
      } catch (IOException ignored) {
        // keep retrying until timeout
      }

      final long now = System.nanoTime();
      if (now >= nextProgressLogAt) {
        logInfo(
            "still waiting for kubeconfig after "
                + elapsedSince(startedAt)
                + " ("
                + describeKubeconfigState(kubeconfigPath)
                + ")");
        nextProgressLogAt = now + LOG_PROGRESS_INTERVAL.toNanos();
      }

      sleep(RETRY_INTERVAL);
    }

    logInfo(
        "kubeconfig wait timed out after "
            + timeout
            + " ("
            + describeKubeconfigState(kubeconfigPath)
            + ")");
    return false;
  }

  private static boolean waitForApiReady(Path kubeconfigPath, Duration timeout) {
    logInfo("waiting for kubernetes API readiness (/readyz)...");
    final long startedAt = System.nanoTime();
    long nextProgressLogAt = startedAt + LOG_PROGRESS_INTERVAL.toNanos();
    String lastSummary = "not yet checked";
    final long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      final CommandResult readyzResult =
          runCommand(
              List.of(
                  "kubectl",
                  "--kubeconfig",
                  kubeconfigPath.toString(),
                  "--insecure-skip-tls-verify=true",
                  "--request-timeout=5s",
                  "get",
                  "--raw=/readyz"),
              Duration.ofSeconds(8));
      if (readyzResult.exitCode() == 0 && readyzResult.stdout().trim().contains("ok")) {
        logInfo("kubernetes API ready after " + elapsedSince(startedAt));
        return true;
      }

      lastSummary = readyzResult.summary();
      final long now = System.nanoTime();
      if (now >= nextProgressLogAt) {
        logInfo(
            "still waiting for API readyz after "
                + elapsedSince(startedAt)
                + " (last result: "
                + lastSummary
                + ")");
        nextProgressLogAt = now + LOG_PROGRESS_INTERVAL.toNanos();
      }

      sleep(RETRY_INTERVAL);
    }

    logInfo(
        "API readiness wait timed out after " + timeout + " (last result: " + lastSummary + ")");
    return false;
  }

  private static ControllerVerification verifyRequiredControllers(
      Path kubeconfigPath, ControlplanePolicy policy, Duration timeout) {
    final List<ControllerRef> requiredControllers = requiredControllers(policy);
    if (requiredControllers.isEmpty()) {
      logInfo("no required controllers configured for readiness gate");
      return new ControllerVerification(true, "no required controllers", List.of());
    }

    for (ControllerRef controllerRef : requiredControllers) {
      final String resourceRef = controllerRef.kind() + "/" + controllerRef.name();

      logInfo("waiting for controller create: " + controllerRef.ref());
      final CommandResult createdResult =
          runCommand(
              List.of(
                  "kubectl",
                  "--kubeconfig",
                  kubeconfigPath.toString(),
                  "--insecure-skip-tls-verify=true",
                  "-n",
                  controllerRef.namespace(),
                  "wait",
                  "--for=create",
                  resourceRef,
                  "--timeout=" + timeout.toSeconds() + "s"),
              timeout.plusSeconds(5));
      if (createdResult.exitCode() != 0) {
        logInfo("controller create wait failed: " + controllerRef.ref());
        return new ControllerVerification(
            false,
            "failed waiting for resource create "
                + controllerRef.ref()
                + " ("
                + createdResult.summary()
                + ")",
            requiredControllerRefs(policy));
      }

      logInfo("waiting for controller rollout: " + controllerRef.ref());
      final CommandResult rolloutResult =
          runCommand(
              List.of(
                  "kubectl",
                  "--kubeconfig",
                  kubeconfigPath.toString(),
                  "--insecure-skip-tls-verify=true",
                  "-n",
                  controllerRef.namespace(),
                  "rollout",
                  "status",
                  resourceRef,
                  "--timeout=" + timeout.toSeconds() + "s"),
              timeout.plusSeconds(5));
      if (rolloutResult.exitCode() != 0) {
        logInfo("controller rollout wait failed: " + controllerRef.ref());
        return new ControllerVerification(
            false,
            "failed rollout status for "
                + controllerRef.ref()
                + " ("
                + rolloutResult.summary()
                + ")",
            requiredControllerRefs(policy));
      }

      logInfo("controller ready: " + controllerRef.ref());
    }

    logInfo("all required controllers are effective");
    return new ControllerVerification(
        true, "all required controllers rolled out", requiredControllerRefs(policy));
  }

  private static String describeKubeconfigState(Path kubeconfigPath) {
    try {
      if (!Files.exists(kubeconfigPath)) {
        return "missing";
      }
      if (!Files.isRegularFile(kubeconfigPath)) {
        return "not-a-regular-file";
      }

      final long size = Files.size(kubeconfigPath);
      if (size <= 0) {
        return "empty-file";
      }

      final String content = Files.readString(kubeconfigPath, StandardCharsets.UTF_8);
      final boolean hasApiVersion = content.contains("apiVersion:");
      final boolean hasClusters = content.contains("clusters:");
      return "size=" + size + ", apiVersion=" + hasApiVersion + ", clusters=" + hasClusters;
    } catch (IOException ex) {
      return "unreadable: " + ex.getMessage();
    }
  }

  private static String elapsedSince(long startedAtNanos) {
    return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos)).toString();
  }

  private static void logInfo(String message) {
    ACTIVE_LOGGER.get().accept(message);
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

    if (policy.manifestLink().platformEnabled()) {
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
      boolean readinessEnabled,
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
          true,
          "Ready",
          "cluster readiness verified (kubeconfig published, API ready, required controllers effective)",
          List.copyOf(requiredControllerRefs));
    }

    private static VerificationResult skipped(List<String> requiredControllerRefs) {
      return new VerificationResult(
          false,
          false,
          false,
          false,
          true,
          "Skipped",
          "cluster readiness checks disabled by configuration (rke2lab:readiness.enabled=false)",
          List.copyOf(requiredControllerRefs));
    }

    private static VerificationResult deferredPreview(List<String> requiredControllerRefs) {
      return new VerificationResult(
          true,
          false,
          false,
          false,
          false,
          "Deferred",
          "cluster readiness checks deferred during preview; execute 'pulumi up' for live verification",
          List.copyOf(requiredControllerRefs));
    }

    private static VerificationResult failed(
        boolean kubeconfigPublished,
        boolean apiReady,
        boolean controllersEffective,
        String summary,
        List<String> requiredControllerRefs) {
      return new VerificationResult(
          true,
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
      outputs.put("clusterReadinessEnabled", readinessEnabled);
      outputs.put("clusterReadinessSkipped", !readinessEnabled);
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
