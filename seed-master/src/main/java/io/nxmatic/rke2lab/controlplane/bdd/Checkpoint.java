package io.nxmatic.rke2lab.controlplane.bdd;

/**
 * The single source of truth for a checkpoint's IDENTITY, consumed by BOTH the BDD layer (the
 * scenario id / {@link ConsultationReport#checkpointId()}) and the Pulumi resources (the resource
 * {@code name} → URN). The resource name is DERIVED from the slug ({@code "seed-" + slug}), so the
 * correspondence between the two layers is structural rather than hand-maintained — this guards
 * against the clusterApi/cluster-api silent-failure pattern documented in CLAUDE.md.
 *
 * <p>Identity ONLY — never topology. The cluster→systemd dependency edge lives on the resource
 * {@code dependsOn}, not here.
 */
public enum Checkpoint {
  SYSTEMD_ADAPTER("systemd-adapter"),
  CLUSTER_READINESS("cluster-readiness");

  private final String slug;

  Checkpoint(String slug) {
    this.slug = slug;
  }

  /** The kebab-case identity used as the BDD scenario id. */
  public String slug() {
    return slug;
  }

  /** The Pulumi resource name, derived from the slug so it cannot drift from the BDD identity. */
  public String resourceName() {
    return "seed-" + slug;
  }
}
