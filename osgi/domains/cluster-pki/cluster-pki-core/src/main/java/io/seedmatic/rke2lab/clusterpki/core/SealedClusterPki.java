package io.seedmatic.rke2lab.clusterpki.core;

import io.seedmatic.rke2lab.clusterpki.contract.AdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterCaBundle;
import io.seedmatic.rke2lab.clusterpki.contract.WebhookServingCredentials;

/**
 * What {@link ClusterSeal} produces in one act: the sops-sealed CA {@link ClusterCaBundle} (filed
 * PLAIN by the scion — it is already sealed), the {@link ClusterAgeKey} identity that decrypts it
 * on the node (filed SEALED), the operator's {@link AdminCredentials} (filed SEALED — it carries
 * the admin private key), and the {@link WebhookServingCredentials} (filed SEALED — it carries the
 * serving private key). The scion stores each at its {@code ClusterPkiCoordinate} case.
 */
public record SealedClusterPki(
    ClusterCaBundle bundle,
    ClusterAgeKey ageKey,
    AdminCredentials adminCredentials,
    WebhookServingCredentials webhookServing) {}
