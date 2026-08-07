package io.nxmatic.rke2lab.cluster.edge;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.jdkhttp.JdkHttpClientFactory;
import io.fabric8.kubernetes.client.readiness.Readiness;
import io.nxmatic.rke2lab.cluster.contract.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.contract.ControllerRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 * <p>Each method is a single, stateless contact: it asks the API server ONE question about the
 * cluster as it is NOW and returns the raw boolean. There is no retry loop, no timeout policy, no
 * phase ordering, no doctor fact — the host owns all of that. An edge "makes the contact and
 * returns a raw fact"; it does not diagnose. The client is built PER call inside the method body
 * (never a field), so a surveyed run — whose scenario bodies are skipped — never opens a socket.
 * SCR publishes it; the host resolves it from the registry.
 *
 * <p>The JDK HttpClient backend is wired via an EXPLICIT {@link JdkHttpClientFactory} rather than
 * the default {@code ServiceLoader} lookup: fabric8 discovers its {@code HttpClient.Factory} by
 * {@code ServiceLoader}, which does not cross bundle wires under OSGi (no spifly here), so the edge
 * hands it the factory directly.
 */
@Component(service = ClusterReadinessContact.class)
public final class Fabric8ClusterContact implements ClusterReadinessContact {

  private static final Logger LOG = LoggerFactory.getLogger(Fabric8ClusterContact.class);

  /** Bounds a single contact (connect + request) so it never blocks the host's wait loop. */
  private static final int CONTACT_TIMEOUT_MILLIS = 5_000;

  @Override
  public boolean isApiReady(Path kubeconfig) {
    try (KubernetesClient client = clientFor(kubeconfig)) {
      // /readyz returns plain "ok" with 200 when the API server is ready; a not-ready server
      // answers non-2xx (fabric8 raises on it) — both collapse to the raw "not ready" fact.
      final String readyz = client.raw("/readyz");
      return readyz != null && readyz.trim().contains("ok");
    } catch (RuntimeException ex) {
      LOG.debug("kube-apiserver /readyz not ok: {}", ex.getMessage());
      return false;
    } catch (Exception ex) {
      LOG.debug("could not read the kubeconfig at {}: {}", kubeconfig, ex.getMessage());
      return false;
    }
  }

  @Override
  public boolean areControllersEffective(Path kubeconfig, List<ControllerRef> controllers) {
    if (controllers.isEmpty()) {
      return true;
    }
    try (KubernetesClient client = clientFor(kubeconfig)) {
      for (ControllerRef controller : controllers) {
        if (!isControllerRolledOut(client, controller)) {
          LOG.debug("controller not effective: {}", controller.ref());
          return false;
        }
      }
      return true;
    } catch (RuntimeException ex) {
      LOG.debug("controller rollout contact failed: {}", ex.getMessage());
      return false;
    } catch (Exception ex) {
      LOG.debug("could not read the kubeconfig at {}: {}", kubeconfig, ex.getMessage());
      return false;
    }
  }

  /**
   * Whether a single controller currently exists and is fully rolled out, read point-in-time: the
   * resource is fetched once (no watch — the host owns the retry loop). A resource not yet created,
   * or of an unrecognised kind, is not effective.
   */
  private static boolean isControllerRolledOut(KubernetesClient client, ControllerRef controller) {
    final String namespace = controller.namespace();
    final String name = controller.name();
    return switch (controller.kind().toLowerCase(Locale.ROOT)) {
      case "deployment" ->
          rolledOut(client.apps().deployments().inNamespace(namespace).withName(name));
      case "daemonset" ->
          rolledOut(client.apps().daemonSets().inNamespace(namespace).withName(name));
      case "statefulset" ->
          rolledOut(client.apps().statefulSets().inNamespace(namespace).withName(name));
      default -> false;
    };
  }

  /** Present in the cluster (fetched once) AND ready — the point-in-time rollout fact. */
  private static boolean rolledOut(Resource<? extends HasMetadata> resource) {
    return Optional.ofNullable(resource.get())
        .map(present -> Readiness.getInstance().isReady(present))
        .orElse(false);
  }

  /**
   * A client bound to the published kubeconfig, TLS-verified natively (the kubeconfig carries the
   * PKI CA). Built purely from the file contents — never from {@code ~/.kube/config} or the
   * environment — with retries disabled so an unreachable cluster fails fast rather than backing
   * off past the contact bound, and the JDK HttpClient factory wired explicitly.
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
