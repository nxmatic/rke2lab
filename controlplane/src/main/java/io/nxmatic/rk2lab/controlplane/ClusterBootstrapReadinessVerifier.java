package io.nxmatic.rk2lab.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.policy.ControlplanePolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  private static final Duration KUBECONFIG_WAIT_TIMEOUT = Duration.ofMinutes(10);

  private static final Duration API_READY_TIMEOUT = Duration.ofMinutes(10);

  private static final Duration CONTROLLER_WAIT_TIMEOUT = Duration.ofMinutes(10);

  private static final Duration RETRY_INTERVAL = Duration.ofSeconds(2);

  private static final Duration LOG_PROGRESS_INTERVAL = Duration.ofSeconds(30);

  private static final Consumer<String> DEFAULT_LOGGER =
      message -> System.out.println("[readiness] " + message);

  private static final ThreadLocal<Consumer<String>> ACTIVE_LOGGER =
      ThreadLocal.withInitial(() -> DEFAULT_LOGGER);

  private ClusterBootstrapReadinessVerifier() {
    // Utility class
  }

  public static VerificationResult verify(BootstrapConfig config, ControlplanePolicy policy) {
    final Path kubeconfigPath = config.kubeconfigRef().toAbsolutePath().normalize();

    logInfo("readiness check enabled");
    logInfo("seed node: " + config.nodeName() + " (project=" + config.incusProject() + ")");
    logInfo("kubeconfig path: " + kubeconfigPath);
    logInfo(
        "timeouts: kubeconfig="
            + KUBECONFIG_WAIT_TIMEOUT
            + ", api="
            + API_READY_TIMEOUT
            + ", controllers="
            + CONTROLLER_WAIT_TIMEOUT);

    if (!waitForSeedNodeBootstrapState(config)) {
      logInfo("readiness failed: seed node systemd/bootstrap gate did not converge in time");
      return VerificationResult.failed(
          false,
          false,
          false,
          "seed node bootstrap gate did not converge (systemd jobs/services + rke2 preconditions) for "
              + config.nodeName()
              + " in project "
              + config.incusProject(),
          requiredControllerRefs(policy));
    }

    if (!waitForKubeconfigPublished(kubeconfigPath)) {
      logInfo("readiness failed: kubeconfig was not published in time");
      return VerificationResult.failed(
          false,
          false,
          false,
          "kubeconfig was not published within timeout at: " + kubeconfigPath,
          requiredControllerRefs(policy));
    }

    if (!waitForApiReady(kubeconfigPath)) {
      logInfo("readiness failed: kubernetes API did not become ready in time");
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
      logInfo("readiness failed: " + controllers.detail());
      return VerificationResult.failed(
          true,
          true,
          false,
          "required bootstrap controllers are not effective: " + controllers.detail(),
          controllers.requiredControllerRefs());
    }

    logInfo("readiness complete: kubeconfig published, API ready, required controllers effective");
    return VerificationResult.ready(controllers.requiredControllerRefs());
  }

  public static VerificationResult verify(
      BootstrapConfig config, ControlplanePolicy policy, Consumer<String> logger) {
    final Consumer<String> previous = ACTIVE_LOGGER.get();
    ACTIVE_LOGGER.set(logger == null ? DEFAULT_LOGGER : logger);
    try {
      return verify(config, policy);
    } finally {
      ACTIVE_LOGGER.set(previous);
    }
  }

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

  private static boolean waitForKubeconfigPublished(Path kubeconfigPath) {
    logInfo("waiting for kubeconfig publication...");
    final long startedAt = System.nanoTime();
    long nextProgressLogAt = startedAt + LOG_PROGRESS_INTERVAL.toNanos();
    final long deadlineNanos = System.nanoTime() + KUBECONFIG_WAIT_TIMEOUT.toNanos();
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
            + KUBECONFIG_WAIT_TIMEOUT
            + " ("
            + describeKubeconfigState(kubeconfigPath)
            + ")");
    return false;
  }

  private static boolean waitForSeedNodeBootstrapState(BootstrapConfig config) {
    logInfo("waiting for seed node systemd adapter runtime status...");
    final long startedAt = System.nanoTime();
    long nextProgressLogAt = startedAt + LOG_PROGRESS_INTERVAL.toNanos();
    final long deadlineNanos = System.nanoTime() + KUBECONFIG_WAIT_TIMEOUT.toNanos();

    String lastSummary = "not yet checked";
    while (System.nanoTime() < deadlineNanos) {
      final Map<String, Object> statusSnapshot = readSystemdAdapterSnapshot(config);
      final String probeStatus = stringValue(statusSnapshot.getOrDefault("status", "unknown"));
      final boolean runtimeReady = toBoolean(statusSnapshot.get("runtimePrecheckReady"));
      final int pendingJobCount = toInt(statusSnapshot.get("pendingJobs"), -1);
      final int failedUnitCount = toInt(statusSnapshot.get("failedUnits"), -1);
      final String mandatoryTarget =
          stringValue(statusSnapshot.getOrDefault("mandatoryTarget", "rke2lab.target"));
      final String mandatoryTargetState =
          stringValue(statusSnapshot.getOrDefault("mandatoryTargetState", "unknown"));
      final String adapterSummary = stringValue(statusSnapshot.getOrDefault("summary", "n/a"));
      final String endpoint =
          stringValue(
              statusSnapshot.getOrDefault("endpoint", config.systemdAdapterStatusEndpoint()));

      if ("ok".equalsIgnoreCase(probeStatus) && runtimeReady) {
        logInfo("seed node bootstrap preconditions ready after " + elapsedSince(startedAt));
        return true;
      }

      lastSummary =
          "endpoint="
              + endpoint
              + ", status="
              + probeStatus
              + ", mandatoryTarget="
              + mandatoryTarget
              + "="
              + mandatoryTargetState
              + ", pendingJobs="
              + pendingJobCount
              + ", failedUnits="
              + failedUnitCount
              + ", summary="
              + adapterSummary;

      final long now = System.nanoTime();
      if (now >= nextProgressLogAt) {
        logInfo(
            "still waiting for seed node bootstrap preconditions after "
                + elapsedSince(startedAt)
                + " ("
                + lastSummary
                + ")");
        nextProgressLogAt = now + LOG_PROGRESS_INTERVAL.toNanos();
      }

      sleep(RETRY_INTERVAL);
    }

    logInfo(
        "seed node bootstrap precondition wait timed out after "
            + KUBECONFIG_WAIT_TIMEOUT
            + " (last result: "
            + lastSummary
            + ")");
    return false;
  }

  private static Map<String, Object> readSystemdAdapterSnapshot(BootstrapConfig config) {
    final CommandResult result =
        runCommand(
            incusExec(config, "sh", "-lc", adapterStatusCommand(config)), Duration.ofSeconds(8));

    if (result.exitCode() != 0) {
      return Map.of(
          "status",
          "command-failed",
          "summary",
          result.summary(),
          "capturedAt",
          Instant.now().toString(),
          "endpoint",
          config.systemdAdapterStatusEndpoint().toString());
    }

    try {
      final LinkedHashMap<String, Object> parsed =
          new LinkedHashMap<>(
              JSON_MAPPER.readValue(result.stdout(), new TypeReference<Map<String, Object>>() {}));
      parsed.put("status", "ok");
      parsed.putIfAbsent("capturedAt", Instant.now().toString());
      parsed.put("endpoint", config.systemdAdapterStatusEndpoint().toString());
      return Map.copyOf(parsed);
    } catch (IOException ex) {
      return Map.of(
          "status",
          "parse-error",
          "summary",
          "failed to parse adapter status JSON: " + ex.getMessage(),
          "capturedAt",
          Instant.now().toString(),
          "endpoint",
          config.systemdAdapterStatusEndpoint().toString());
    }
  }

  private static String adapterStatusCommand(BootstrapConfig config) {
    return "curl --silent --show-error --fail --max-time 5 "
        + shellQuote(config.systemdAdapterStatusEndpoint().toString());
  }

  private static boolean toBoolean(Object value) {
    if (value instanceof Boolean boolValue) {
      return boolValue;
    }
    return Boolean.parseBoolean(stringValue(value));
  }

  private static int toInt(Object value, int fallback) {
    if (value instanceof Number numberValue) {
      return numberValue.intValue();
    }
    final String raw = stringValue(value);
    if (raw.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String stringValue(Object value) {
    return value == null ? "" : value.toString();
  }

  private static boolean waitForApiReady(Path kubeconfigPath) {
    logInfo("waiting for kubernetes API readiness (/readyz)...");
    final long startedAt = System.nanoTime();
    long nextProgressLogAt = startedAt + LOG_PROGRESS_INTERVAL.toNanos();
    String lastSummary = "not yet checked";
    final long deadlineNanos = System.nanoTime() + API_READY_TIMEOUT.toNanos();
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
        "API readiness wait timed out after "
            + API_READY_TIMEOUT
            + " (last result: "
            + lastSummary
            + ")");
    return false;
  }

  private static ControllerVerification verifyRequiredControllers(
      Path kubeconfigPath, ControlplanePolicy policy) {
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
                  "--timeout=" + CONTROLLER_WAIT_TIMEOUT.toSeconds() + "s"),
              CONTROLLER_WAIT_TIMEOUT.plusSeconds(5));
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
                  "--timeout=" + CONTROLLER_WAIT_TIMEOUT.toSeconds() + "s"),
              CONTROLLER_WAIT_TIMEOUT.plusSeconds(5));
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
