package io.nxmatic.rke2lab.incus.ingress;

import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The incus domain's DUAL-REALM seed coordinate — the one document the host GROW fetches. It lives
 * HERE, not in {@code IncusCoordinate}: that enum is {@code incus-contract} ({@code type=contract},
 * OSGi-only), so the pure-host GROW cannot compile against it, and spelling the slug as a magic
 * string host-side is the very mismatch the single-source-of-truth discipline forbids. Both realms
 * reference this enum instead — the scion (OSGi) to {@code store}, the host GROW to {@code fetch} —
 * because this module is {@code type=dual-realm}, present flat in both realms.
 *
 * <p>Its slug matches the {@code @SeedContract} of {@link InstanceGrowPlan}, which {@code
 * SeedCodec} verifies at decode. Domain is {@code "incus"}, the same as {@code IncusCoordinate}:
 * this is the incus domain speaking to itself across the realm boundary, not a second domain.
 */
public enum IncusGrowCoordinate implements SeedCoordinate {
  INSTANCE_GROW_PLAN("instance-grow-plan"),

  // The deterministic cluster PKI, sealed host-side once per cluster and posed on the instance's
  // devlxd config by the GROW (NOT carried in InstanceGrowPlan — that is PLAIN at rest, and the age
  // key must stay SEALED). Two cases, two sensitivities: the CA bundle is already a sops blob
  // (PLAIN
  // in the cellar), the age identity that decrypts it on the node is SEALED (CellarCipher at rest).
  // See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
  CLUSTER_CA_BUNDLE("cluster-ca-bundle"),
  CLUSTER_AGE_KEY("cluster-age-key");

  private static final String DOMAIN = "incus";

  private final String slug;

  IncusGrowCoordinate(String slug) {
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
