package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * A webhook SERVING TLS certificate, minted from the deterministic cluster {@code server-ca} at
 * seal time: a serverAuth leaf ({@code CN=<first SAN>}) carrying the webhook Service DNS names as
 * {@code subjectAlternativeName}, its private key, and the {@code server-ca} chain that verifies it
 * (the chain ends at the ndh {@code mammoth-skate-tls} root). Three PEM blocks — the cert the
 * webhook server presents, its key, and the CA bundle the apiserver's webhook client trusts.
 *
 * <p>Secret (it carries the serving private key): the seal WHEN files it in the cellar {@link
 * ClusterPkiCoordinate#WEBHOOK_SERVING} SEALED ({@code Sensitivity.SEALED}, CellarCipher at rest).
 * The manifests layer reveals it to render the webhook's serving Secret + the CA bundle the
 * ValidatingWebhookConfiguration pins. A {@code type=dual-realm} record: minted + filed OSGi-side
 * by the seal scion, fetched manifests-side. {@link SeedContract} binds it to the {@code
 * webhook-serving} coordinate for the codec's decode guard. Mirrors {@link AdminCredentials}.
 */
@SeedContract("webhook-serving")
public record WebhookServingCredentials(String certPem, String keyPem, String caBundlePem) {}
