package io.nxmatic.rke2lab.world.gateway.port;

import java.util.Optional;

/**
 * The closed set of document coordinates — the document type and schema key a {@link Document}
 * carries. Lifted from loose strings so a call site cannot name a coordinate that does not exist
 * (the {@code clusterApi}-bug discipline). {@code slug()} is the wire value placed in {@link
 * Document#coordinate()}; the envelope stays neutral and never holds this enum.
 */
public enum Coordinate {
  READINESS_CHECKPOINT("readiness-checkpoint"),
  READINESS_VERDICT("readiness-verdict"),
  CONSULTATION("consultation"),
  /** The raw intervention facts the CLI pushes OSGi-side for canonicalization. */
  INTERVENTION_REQUEST("intervention-request"),
  /** The canonical intervention document OSGi returns and the ledger writer persists. */
  INTERVENTION("intervention"),
  /**
   * One readable entry of a patient's record timeline, produced by the host READ journal: it
   * carries the entry's version + when and the raw consultation-report/expectation output blobs.
   * OSGi folds a sequence of these into one {@code Visit} per entry to rebuild the medical record.
   */
  VISIT("visit");

  private final String slug;

  Coordinate(String slug) {
    this.slug = slug;
  }

  public String slug() {
    return slug;
  }

  /** Resolves a wire coordinate; null/blank/unknown yields empty. */
  public static Optional<Coordinate> parse(String slug) {
    if (slug == null || slug.isBlank()) {
      return Optional.empty();
    }
    for (Coordinate coordinate : values()) {
      if (coordinate.slug.equals(slug)) {
        return Optional.of(coordinate);
      }
    }
    return Optional.empty();
  }
}
