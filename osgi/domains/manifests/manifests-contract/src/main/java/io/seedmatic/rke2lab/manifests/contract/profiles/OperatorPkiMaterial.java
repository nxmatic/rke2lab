// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * The operator's admin PKI published to synth-time layers via {@code ManifestSynthesisContext} — an
 * admin client certificate ({@code CN=rke2lab-admin, O=system:masters}) minted from the cluster
 * {@code client-ca}, its private key, and the {@code server-ca} chain that verifies kube-apiserver.
 * Three PEM blocks, endpoint-INDEPENDENT: {@link #kubeconfig(String, String)} wraps them around a
 * per-consumer endpoint (the operator reaches the node over its mDNS name; in-cluster consumers
 * reach it over the kube-vip VIP).
 *
 * <p>The manifests-side MIRROR of the cluster-pki {@code AdminCredentials} dual-realm record: the
 * manifests scion reveals {@code AdminCredentials} from the cellar in-container (SEALED at rest)
 * and translates it here before handing it to synthesis, so no {@code cluster-pki} type ever
 * crosses into the manifests domain — the gate boundary stays clean, exactly as {@link
 * SopsAgeMaterial} and {@link IncusIdentityMaterial} keep their producing domains out of manifests.
 * The renderer is duplicated (not shared) for the same reason: a shared renderer would need a
 * module both realms depend on, re-coupling them.
 *
 * <p>Absence — no cluster PKI sealed yet (unit tests, a bare survey) — is carried as an empty
 * {@code Optional<OperatorPkiMaterial>} on the context, never a placeholder: a present material
 * always holds real PEM, so the kubeconfig unit renders unconditionally.
 */
public record OperatorPkiMaterial(String clientCertPem, String clientKeyPem, String caCertPem) {

  public OperatorPkiMaterial {
    clientCertPem = Objects.requireNonNull(clientCertPem, "clientCertPem");
    clientKeyPem = Objects.requireNonNull(clientKeyPem, "clientKeyPem");
    caCertPem = Objects.requireNonNull(caCertPem, "caCertPem");
  }

  /**
   * Render a kubeconfig around these credentials for one endpoint: the {@code server-ca} chain as
   * the cluster CA (natively trusted, rooted at the operator's ndh root), the admin client cert +
   * key as the user. One cluster + one context, current. The three PEM blocks ride base64 as kube's
   * {@code *-data} fields.
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
