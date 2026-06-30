package io.nxmatic.rke2lab.exchange.port;

import java.util.Optional;

/**
 * The closed set of document owners — the value carried in {@link Document#domain()}. Lifted from a
 * loose string so a call site cannot name an owner that does not exist (the {@code clusterApi}-bug
 * discipline). This is the exchange's own owner axis, NOT the host's provisioning-domain catalog
 * nor the manifest deployment-domain catalog: a different axis and a different layer (this seam
 * leaf must not depend on the host). {@code slug()} is the wire value placed in {@link
 * Document#domain()}; the envelope stays neutral and never holds this enum.
 */
public enum Domain {
  DOCTOR("doctor");

  private final String slug;

  Domain(String slug) {
    this.slug = slug;
  }

  public String slug() {
    return slug;
  }

  /** Resolves a wire domain; null/blank/unknown yields empty. */
  public static Optional<Domain> parse(String slug) {
    if (slug == null || slug.isBlank()) {
      return Optional.empty();
    }
    for (Domain domain : values()) {
      if (domain.slug.equals(slug)) {
        return Optional.of(domain);
      }
    }
    return Optional.empty();
  }
}
