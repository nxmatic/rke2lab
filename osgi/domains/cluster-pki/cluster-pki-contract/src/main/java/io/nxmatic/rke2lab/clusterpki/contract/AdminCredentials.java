package io.nxmatic.rke2lab.clusterpki.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The operator's admin credentials, endpoint-INDEPENDENT: an admin client certificate ({@code
 * CN=rke2lab-admin, O=system:masters}) minted from the deterministic cluster {@code client-ca}, its
 * private key, and the {@code server-ca} chain that verifies kube-apiserver — the chain ends at the
 * ndh {@code mammoth-skate-tls} root, so the operator trusts it natively. Three PEM blocks, no
 * endpoint: the server URL is a per-consumer fact (the operator reaches the node over its mDNS name
 * {@code <cluster>-<node>.local}; in-cluster consumers reach it over the kube-vip VIP), so {@link
 * #kubeconfig(String, String)} wraps these three blocks around a supplied endpoint. The seal only
 * mints what is stable across re-grows.
 *
 * <p>Secret (it carries the admin private key): the seal WHEN files it in the cellar {@link
 * ClusterPkiCoordinate#ADMIN_CREDENTIALS} SEALED ({@code Sensitivity.SEALED}, CellarCipher at
 * rest). The host reveals it after the grow and writes the operator kubeconfig to {@code
 * kubeconfigRef} ({@code .local.d/kubeconfig.yaml}) with the mDNS endpoint — the path the readiness
 * probe reads; the manifests layer reveals it again to render the in-cluster {@code
 * <cluster>-kubeconfig} Secret with the VIP endpoint. A {@code type=dual-realm} record: minted +
 * filed OSGi-side by the seal scion, fetched host-side and manifests-side. {@link SeedContract}
 * binds it to the {@code admin-credentials} coordinate for the codec's decode guard. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedContract("admin-credentials")
public record AdminCredentials(String clientCertPem, String clientKeyPem, String caCertPem) {

  /**
   * Render a kubeconfig around these credentials for one endpoint: the {@code server-ca} chain as
   * the cluster CA (natively trusted, rooted at mammoth-skate-tls), the admin client cert + key as
   * the user. One cluster + one context, current. The three PEM blocks ride base64 as kube's {@code
   * *-data} fields. The endpoint is the caller's choice — the operator supplies the mDNS URL, the
   * manifests layer supplies the VIP URL.
   */
  public String kubeconfig(String clusterName, String server) {
    final Base64.Encoder b64 = Base64.getEncoder();
    final String ca = b64.encodeToString(caCertPem.getBytes(StandardCharsets.UTF_8));
    final String cert = b64.encodeToString(clientCertPem.getBytes(StandardCharsets.UTF_8));
    final String key = b64.encodeToString(clientKeyPem.getBytes(StandardCharsets.UTF_8));
    // The user name is <cluster>-admin, unique per cluster: kubeconfig users/clusters/contexts are
    // global lists merged BY NAME, so a shared "rke2lab-admin" would collapse across clusters and
    // both contexts would point at one (wrong) user cert.
    final String user = clusterName + "-admin";
    return """
        apiVersion: v1
        kind: Config
        clusters:
          - name: %s
            cluster:
              server: %s
              certificate-authority-data: %s
        users:
          - name: %s
            user:
              client-certificate-data: %s
              client-key-data: %s
        contexts:
          - name: %s
            context:
              cluster: %s
              user: %s
        current-context: %s
        """
        .formatted(
            clusterName, server, ca, user, cert, key, clusterName, clusterName, user, clusterName);
  }
}
