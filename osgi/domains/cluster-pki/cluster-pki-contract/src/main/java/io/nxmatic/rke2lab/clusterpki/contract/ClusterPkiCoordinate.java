package io.nxmatic.rke2lab.clusterpki.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The cluster-pki domain's DUAL-REALM seed coordinate — the two cases the seal scion files and the
 * host GROW fetches. It lives HERE (a {@code type=dual-realm} contract) precisely so the pure-host
 * GROW, which runs outside Felix, can reference it without depending on an OSGi-only bundle — the
 * incus-ingress {@code IncusGrowCoordinate} lesson, applied to the producing domain.
 *
 * <p>Each slug matches the {@code @SeedContract} of the record filed under it ({@link
 * ClusterCaBundle}, {@link ClusterAgeKey}), which {@code SeedCodec} verifies at decode. Domain is
 * {@code "cluster-pki"} — the seal scion (OSGi) stores, the incus GROW (host) fetches, across the
 * realm boundary com.pulumi imposes. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
public enum ClusterPkiCoordinate implements SeedCoordinate {

  // The deterministic cluster CA, sealed host-side once per cluster and posed on the instance's
  // devlxd config by the GROW. Two cases, two sensitivities: the CA bundle is already a sops blob
  // (PLAIN in the cellar), the age identity that decrypts it on the node is SEALED (CellarCipher).
  CLUSTER_CA_BUNDLE("cluster-ca-bundle"),
  CLUSTER_AGE_KEY("cluster-age-key");

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
