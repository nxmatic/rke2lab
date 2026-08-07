package io.nxmatic.rke2lab.clusterpki.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The cluster-pki domain's DUAL-REALM seed coordinate — the two cases the seal scion files and the
 * host GROW fetches. It lives HERE (a {@code type=dual-realm} contract) precisely so the pure-host
 * GROW, which runs outside Felix, can reference it without depending on an OSGi-only bundle — the
 * incus-ingress {@code IncusGrowCoordinate} lesson, applied to the producing domain.
 *
 * <p>Each slug matches the {@code @SeedContract} of the record filed under it ({@link
 * ClusterCaBundle}, {@link ClusterAgeKey}, {@link AdminCredentials}), which {@code SeedCodec}
 * verifies at decode. Domain is {@code "cluster-pki"} — the seal scion (OSGi) stores, the host
 * fetches (the incus GROW poses the node cases on devlxd; the kubeconfig write consumes the admin
 * case), across the realm boundary com.pulumi imposes. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
public enum ClusterPkiCoordinate implements SeedCoordinate {

  // The deterministic cluster CA, sealed host-side once per cluster and posed on the instance's
  // devlxd config by the GROW. Two cases, two sensitivities: the CA bundle is already a sops blob
  // (PLAIN in the cellar), the age identity that decrypts it on the node is SEALED (CellarCipher).
  CLUSTER_CA_BUNDLE("cluster-ca-bundle"),
  CLUSTER_AGE_KEY("cluster-age-key"),

  // The operator's admin credentials (admin client cert + key + server-ca chain), minted from the
  // client-ca at seal time. SEALED — it carries the admin private key. The host reveals it after
  // the grow and writes the operator kubeconfig the readiness probe reads.
  ADMIN_CREDENTIALS("admin-credentials");

  private static final String DOMAIN = "cluster-pki";

  private final String slug;

  ClusterPkiCoordinate(String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
