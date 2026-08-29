// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.Objects;

/**
 * The webhook SERVING TLS material published to synth-time layers via {@code
 * ManifestSynthesisContext} — a serverAuth leaf minted from the cluster {@code server-ca} carrying
 * the flox-controller webhook Service DNS names in its SAN, its private key, and the {@code
 * server-ca} chain the apiserver's webhook client trusts. Three PEM blocks: the cert the webhook
 * server presents, its key, and the CA bundle the ValidatingWebhookConfiguration pins.
 *
 * <p>The manifests-side MIRROR of the cluster-pki {@code WebhookServingCredentials} dual-realm
 * record: the manifests scion reveals {@code WebhookServingCredentials} from the cellar
 * in-container (SEALED at rest) and translates it here before handing it to synthesis, so no {@code
 * cluster-pki} type ever crosses into the manifests domain — the gate boundary stays clean, exactly
 * as {@link OperatorPkiMaterial} keeps its producing domain out of manifests.
 *
 * <p>Absence — no cluster PKI sealed yet (unit tests, a bare survey) — is carried as an empty
 * {@code Optional<WebhookServingMaterial>} on the context, never a placeholder: a present material
 * always holds real PEM.
 */
public record WebhookServingMaterial(
    String servingCertPem, String servingKeyPem, String caBundlePem) {

  public WebhookServingMaterial {
    servingCertPem = Objects.requireNonNull(servingCertPem, "servingCertPem");
    servingKeyPem = Objects.requireNonNull(servingKeyPem, "servingKeyPem");
    caBundlePem = Objects.requireNonNull(caBundlePem, "caBundlePem");
  }
}
