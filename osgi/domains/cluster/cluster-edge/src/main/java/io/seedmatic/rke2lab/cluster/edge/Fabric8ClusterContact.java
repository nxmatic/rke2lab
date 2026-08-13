package io.seedmatic.rke2lab.cluster.edge;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.jdkhttp.JdkHttpClientFactory;
import io.fabric8.kubernetes.client.readiness.Readiness;
import io.seedmatic.rke2lab.cluster.contract.ClusterReadinessContact;
import io.seedmatic.rke2lab.cluster.contract.ClusterReadinessSnapshot;
import io.seedmatic.rke2lab.cluster.contract.ControllerRef;
import io.seedmatic.rke2lab.osgi.runtime.readiness.ReadinessAwait;
import io.seedmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised cluster edge: the single door toward the live cluster's kube-apiserver. It
 * implements the cluster domain's {@link ClusterReadinessContact} seam through a typed fabric8
 * {@link KubernetesClient} built from a published kubeconfig — no {@code kubectl} shell, no {@code
 * --insecure-skip-tls-verify}. The kubeconfig carries the deterministic PKI's CA (the ndh {@code
 * mammoth-skate-tls} root), so TLS is verified natively; a client cert minted from the cluster
 * {@code client-ca} authenticates the operator.
 *
 * <p>Readiness is AWAITED, not sampled once: the {@link ReadinessAwait} skeleton bounds the reach —
 * retrying {@code /readyz} until the budget's connect deadline (a cold boot / fresh image re-seed
 * lives here) — then, once the API answers, awaits every required controller rolling out via
 * fabric8 {@code waitUntilCondition} until the ready deadline. This is where the deadline-poll the
 * BDD migration had left one-shot now lives, so a gate probed during {@code grow} waits instead of
 * failing the instant the cluster is not yet up.
 *
 * <p>The client is built PER call inside the method body (never a field), so a surveyed run — whose
 * scenario bodies are skipped — never opens a socket. SCR publishes the edge; the host resolves it
 * from the registry.
 *
 * <p>The JDK HttpClient backend is wired via an EXPLICIT {@link JdkHttpClientFactory} rather than
 * the default {@code ServiceLoader} lookup: fabric8 discovers its {@code HttpClient.Factory} by
 * {@code ServiceLoader}, which does not cross bundle wires under OSGi (no spifly here), so the edge
 * hands it the factory directly.
 */
@Component(service = ClusterReadinessContact.class)
public final class Fabric8ClusterContact implements ClusterReadinessContact {

  private static final Logger LOG = LoggerFactory.getLogger(Fabric8ClusterContact.class);

  /** Bounds a single connect + request so one reach attempt never blocks the retry loop. */
  private static final int CONTACT_TIMEOUT_MILLIS = 5_000;

  @Override
  public ClusterReadinessSnapshot awaitReady(
      Path kubeconfig, List<ControllerRef> controllers, ReadinessBudget budget) {
    final ReadinessAwait readiness =
        new ReadinessAwait(
            budget.interval(),
            budget.connect(),
            budget.ready(),
            message -> LOG.info("cluster readiness via {}: {}", kubeconfig, message));

    final Supplier<Optional<KubernetesClient>> connect = () -> reachReadyApi(kubeconfig);
    final BiFunction<KubernetesClient, Duration, ClusterReadinessSnapshot> converge =
        (client, readyBudget) -> awaitControllers(client, controllers, readyBudget);

    try {
      return readiness.await(connect, converge);
    } catch (ReadinessAwait.ReadinessAwaitException apiNeverReady) {
      // The reach gave up: the apiserver never answered /readyz=ok within the connect deadline. Not
      // a throw for the checkpoint — a false apiReady facet it reads and diagnoses (API_NOT_READY).
      LOG.debug("kube-apiserver never became ready: {}", apiNeverReady.getMessage());
      return ClusterReadinessSnapshot.apiNotReady(
          "kube-apiserver /readyz never ok within " + budget.connect() + " at " + kubeconfig);
    }
  }

  /**
   * One reach attempt: build a client and ask {@code /readyz}. A ready apiserver hands back the
   * OPEN client (the skeleton's {@link java.lang.AutoCloseable} channel — it closes it after the
   * convergence wait). A not-ready apiserver closes the client and returns empty so the reach loop
   * retries; an unusable kubeconfig throws, which the skeleton remembers as the last "not up yet"
   * cause.
   */
  private static Optional<KubernetesClient> reachReadyApi(Path kubeconfig) {
    final KubernetesClient client;
    try {
      client = clientFor(kubeconfig);
    } catch (Exception cannotBuild) {
      throw new IllegalStateException("kubeconfig not usable yet at " + kubeconfig, cannotBuild);
    }
    try {
      // /readyz returns plain "ok" with 200 when the API server is ready; a not-ready server
      // answers non-2xx (fabric8 raises on it) — both collapse to "retry".
      final String readyz = client.raw("/readyz");
      if (readyz != null && readyz.trim().contains("ok")) {
        return Optional.of(client);
      }
    } catch (RuntimeException notReadyYet) {
      LOG.debug("kube-apiserver /readyz not ok yet: {}", notReadyYet.getMessage());
    }
    client.close();
    return Optional.empty();
  }

  /**
   * Await every required controller rolling out over the live client, bounded by {@code
   * readyBudget} (shared across the controllers). An empty list is vacuously effective; the first
   * controller that does not roll out within the remaining budget makes the snapshot not-effective.
   */
  private static ClusterReadinessSnapshot awaitControllers(
      KubernetesClient client, List<ControllerRef> controllers, Duration readyBudget) {
    if (controllers.isEmpty()) {
      return ClusterReadinessSnapshot.ready();
    }
    final long deadline = System.nanoTime() + readyBudget.toNanos();
    for (ControllerRef controller : controllers) {
      final long remainingSeconds = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000_000L);
      if (!controllerRolledOut(client, controller, remainingSeconds)) {
        return ClusterReadinessSnapshot.controllersNotEffective(
            "controller not effective within budget: " + controller.ref());
      }
    }
    return ClusterReadinessSnapshot.ready();
  }

  /**
   * Whether a single controller rolls out within {@code timeoutSeconds}: fabric8 {@code
   * waitUntilCondition} blocks until the resource exists AND is ready, or the timeout elapses (a
   * timeout throws, caught as not-effective). An unrecognised kind is never effective.
   */
  private static boolean controllerRolledOut(
      KubernetesClient client, ControllerRef controller, long timeoutSeconds) {
    final String ns = controller.namespace();
    final String name = controller.name();
    try {
      return switch (controller.kind().toLowerCase(Locale.ROOT)) {
        case "deployment" ->
            waitUntilRolledOut(
                client.apps().deployments().inNamespace(ns).withName(name), timeoutSeconds);
        case "daemonset" ->
            waitUntilRolledOut(
                client.apps().daemonSets().inNamespace(ns).withName(name), timeoutSeconds);
        case "statefulset" ->
            waitUntilRolledOut(
                client.apps().statefulSets().inNamespace(ns).withName(name), timeoutSeconds);
        default -> false;
      };
    } catch (RuntimeException notRolledOut) {
      LOG.debug("controller {} not rolled out within budget: {}", controller.ref(), notRolledOut);
      return false;
    }
  }

  /**
   * Block until the resource exists and is ready, or {@code timeoutSeconds} elapses (then throws).
   */
  private static <T extends HasMetadata> boolean waitUntilRolledOut(
      Resource<T> resource, long timeoutSeconds) {
    resource.waitUntilCondition(
        present -> present != null && Readiness.getInstance().isReady(present),
        timeoutSeconds,
        TimeUnit.SECONDS);
    return true;
  }

  /**
   * A client bound to the published kubeconfig, TLS-verified natively (the kubeconfig carries the
   * PKI CA). Built purely from the file contents — never from {@code ~/.kube/config} or the
   * environment — with retries disabled so one reach attempt fails fast rather than backing off
   * past the contact bound, and the JDK HttpClient factory wired explicitly.
   */
  private static KubernetesClient clientFor(Path kubeconfig) throws Exception {
    final Config config = Config.fromKubeconfig(Files.readString(kubeconfig));
    config.setConnectionTimeout(CONTACT_TIMEOUT_MILLIS);
    config.setRequestTimeout(CONTACT_TIMEOUT_MILLIS);
    config.setRequestRetryBackoffLimit(0);
    return new KubernetesClientBuilder()
        .withConfig(config)
        .withHttpClientFactory(new JdkHttpClientFactory())
        .build();
  }
}
