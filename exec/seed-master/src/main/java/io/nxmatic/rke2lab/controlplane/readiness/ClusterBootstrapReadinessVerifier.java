package io.nxmatic.rke2lab.controlplane.readiness;

import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ControllerRef;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.SeedNodeBootstrapWatcher;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
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
 * Deterministic bootstrap readiness verification.
 *
 * <p>An INSTANCE holds the collaborators a readiness run reasons with — the cluster contact, the
 * bootstrap policy, the systemd runtime-status snapshot, and the progress logger — so the per-phase
 * checks and their retry waiters read them as fields instead of threading them through every call.
 * Checks in canonical order:
 *
 * <ol>
 *   <li>kubeconfig is published at expected host path.
 *   <li>Kubernetes API responds via kubeconfig and reports readyz=ok.
 *   <li>Required controllers (derived from bootstrap policy) are present and rolled out.
 * </ol>
 *
 * <p>The {@link VerificationResult} projection factories stay {@code static}: they build the
 * host/Pulumi-facing output contract from the policy alone, and a caller that only needs the
 * verdict (readiness disabled, or the terminal ready/failed projection) must not be forced to hold
 * a contact/runtime-status it never uses.
 */
public final class ClusterBootstrapReadinessVerifier {

  private static final Duration RETRY_INTERVAL = Duration.ofSeconds(2);

  private static final Duration LOG_PROGRESS_INTERVAL = Duration.ofSeconds(30);

  private final ClusterReadinessContact contact;
  private final ControlplanePolicy policy;
  private final SeedSystemdAdapterRuntimeStatusSnapshot runtimeStatus;
  private final Consumer<String> logger;

  public ClusterBootstrapReadinessVerifier(
      ClusterReadinessContact contact,
      ControlplanePolicy policy,
      SeedSystemdAdapterRuntimeStatusSnapshot runtimeStatus,
      Consumer<String> logger) {
    this.contact = contact;
    this.policy = policy;
    this.runtimeStatus = runtimeStatus;
    this.logger = logger;
  }

  /**
   * One readiness phase checked in isolation — the per-phase seam the BDD checkpoint plays against
   * (a live {@code ClusterReadinessProbe} maps this to an {@code ObservationView}). The verifier
   * stays free of any {@code bdd} types (no package cycle).
   */
  public PhaseOutcome checkKubeconfigPublished(BootstrapConfig config) {
    // Phase-0 gate (preserved from the former verify()): the seed node's systemd/bootstrap
    // preconditions must converge before the kubeconfig can appear. Folded into the first
    // phase so the live ordering is unchanged.
    final Duration timeout = config.readinessTimeout();
    if (!SeedNodeBootstrapWatcher.waitForBootstrapPreconditions(
        config,
        runtimeStatus,
        new SeedNodeBootstrapWatcher.WaitConfig(timeout, RETRY_INTERVAL, LOG_PROGRESS_INTERVAL),
        logger)) {
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
  }

  public PhaseOutcome checkApiReady(BootstrapConfig config) {
    final Path kubeconfigPath = config.kubeconfigRef().toAbsolutePath().normalize();
    final boolean ok = waitForApiReady(kubeconfigPath, config.readinessTimeout());
    return new PhaseOutcome(
        ok, ok ? "kubernetes API reports readyz=ok" : "kubernetes API did not report readyz=ok");
  }

  public PhaseOutcome checkControllersEffective(BootstrapConfig config) {
    final Path kubeconfigPath = config.kubeconfigRef().toAbsolutePath().normalize();
    final ControllerVerification controllers =
        verifyRequiredControllers(kubeconfigPath, config.readinessTimeout());
    return new PhaseOutcome(controllers.ready(), controllers.detail());
  }

  /** Outcome of a single readiness phase: passed, plus a human detail line. */
  public record PhaseOutcome(boolean ok, String detail) {}

  public static VerificationResult skipped(ControlplanePolicy policy, Consumer<String> logger) {
    logger.accept("readiness check disabled by configuration (rke2lab:readiness.enabled=false)");
    return VerificationResult.skipped(requiredControllerRefs(policy));
  }

  /**
   * Projection factories the BDD checkpoint uses to build a {@link VerificationResult} from the
   * per-phase outcomes it played — the verifier stays the owner of result construction, so the
   * output contract (handoffReady → nextStep, bootstrapStatus, the output keys) is produced in one
   * place whether reached via the phases or via the scenario.
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

  private boolean waitForKubeconfigPublished(Path kubeconfigPath, Duration timeout) {
    logger.accept("waiting for kubeconfig publication...");
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
            logger.accept("kubeconfig is published after " + elapsedSince(startedAt));
            return true;
          }
        }
      } catch (IOException ignored) {
        // keep retrying until timeout
      }

      final long now = System.nanoTime();
      if (now >= nextProgressLogAt) {
        logger.accept(
            "still waiting for kubeconfig after "
                + elapsedSince(startedAt)
                + " ("
                + describeKubeconfigState(kubeconfigPath)
                + ")");
        nextProgressLogAt = now + LOG_PROGRESS_INTERVAL.toNanos();
      }

      sleep(RETRY_INTERVAL);
    }

    logger.accept(
        "kubeconfig wait timed out after "
            + timeout
            + " ("
            + describeKubeconfigState(kubeconfigPath)
            + ")");
    return false;
  }

  /**
   * The host owns the retry loop and the timeout policy; the {@link ClusterReadinessContact} edge
   * answers ONE point-in-time question per poll. Each {@code isApiReady} call is stateless — the
   * waiting lives here, not in the edge.
   */
  private boolean waitForApiReady(Path kubeconfigPath, Duration timeout) {
    logger.accept("waiting for kubernetes API readiness (/readyz)...");
    final long startedAt = System.nanoTime();
    long nextProgressLogAt = startedAt + LOG_PROGRESS_INTERVAL.toNanos();
    final long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      if (contact.isApiReady(kubeconfigPath)) {
        logger.accept("kubernetes API ready after " + elapsedSince(startedAt));
        return true;
      }

      final long now = System.nanoTime();
      if (now >= nextProgressLogAt) {
        logger.accept("still waiting for API readyz after " + elapsedSince(startedAt));
        nextProgressLogAt = now + LOG_PROGRESS_INTERVAL.toNanos();
      }

      sleep(RETRY_INTERVAL);
    }

    logger.accept("API readiness wait timed out after " + timeout);
    return false;
  }

  /**
   * The host projects the policy into the required {@link ControllerRef}s and owns the retry loop;
   * the edge answers the single point-in-time "are these effective now?" question per poll. The
   * edge never sees the policy — only the projected refs.
   */
  private ControllerVerification verifyRequiredControllers(Path kubeconfigPath, Duration timeout) {
    final List<ControllerRef> requiredControllers = requiredControllers(policy);
    if (requiredControllers.isEmpty()) {
      logger.accept("no required controllers configured for readiness gate");
      return new ControllerVerification(true, "no required controllers", List.of());
    }

    logger.accept("waiting for required controllers to become effective...");
    final long startedAt = System.nanoTime();
    long nextProgressLogAt = startedAt + LOG_PROGRESS_INTERVAL.toNanos();
    final long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      if (contact.areControllersEffective(kubeconfigPath, requiredControllers)) {
        logger.accept("all required controllers are effective after " + elapsedSince(startedAt));
        return new ControllerVerification(
            true, "all required controllers rolled out", requiredControllerRefs(policy));
      }

      final long now = System.nanoTime();
      if (now >= nextProgressLogAt) {
        logger.accept("still waiting for required controllers after " + elapsedSince(startedAt));
        nextProgressLogAt = now + LOG_PROGRESS_INTERVAL.toNanos();
      }

      sleep(RETRY_INTERVAL);
    }

    logger.accept("required-controller wait timed out after " + timeout);
    return new ControllerVerification(
        false,
        "required controllers not effective within " + timeout,
        requiredControllerRefs(policy));
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

  private record ControllerVerification(
      boolean ready, String detail, List<String> requiredControllerRefs) {}
}
