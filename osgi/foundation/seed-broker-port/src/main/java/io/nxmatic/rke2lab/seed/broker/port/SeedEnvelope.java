package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The neutral envelope every host↔OSGi crossing carries — the seed sown in and the plant reaped out
 * are both a {@code SeedEnvelope}: a document of a given type, owned by a domain, whose body is a
 * serialized JSON {@code String}, carrying a CLEAR {@link Trail} of provenance (its fil d'Ariane).
 * The host and OSGi share no data type — only this record and its fields cross, all either flat
 * (JDK) types or seam-owned records ({@link Trail}/{@link Breadcrumb}, themselves flat-composed),
 * so the seam references nothing a bundle owns. Each world parses/serializes the {@code payload}
 * with ITS OWN jackson (the host's flat one; a bundle's own) — no jackson type ever crosses the
 * boundary, which is what keeps a {@code type=seam} pure (a {@code JsonNode} payload once leaked
 * the jackson bundle across the flat seam and caused a {@code LinkageError} in-container).
 *
 * <p>{@code coordinate} is the document type and the schema key; {@code domain} names the owner.
 * The {@code trail} rides OUTSIDE the {@code payload}, so it stays readable even when the payload
 * is SEALED — a secured value's lineage is traceable without the passphrase. See
 * docs/architecture/osgi/seed-broker-spec.adoc.
 */
public record SeedEnvelope(String domain, String coordinate, String payload, Trail trail) {

  /**
   * Wrap a payload under a {@link SeedCoordinate} with an EMPTY trail — the single-source factory:
   * the coordinate carries BOTH the domain and the slug, so a call site names only the coordinate
   * and the payload, never restating the two wire fields (the redundancy the removed central {@code
   * Domain} enum used to invite). The cellar stamps the trail afterwards via {@link #withTrail}.
   */
  public static SeedEnvelope of(SeedCoordinate coordinate, String payload) {
    return new SeedEnvelope(coordinate.domain(), coordinate.slug(), payload, Trail.empty());
  }

  /**
   * Wrap a payload from RAW wire strings with an EMPTY trail — the twin of {@link #of} for a caller
   * that holds a {@code domain}/{@code coordinate} as strings, not a {@link SeedCoordinate} enum
   * (an {@code *AmendReflector} re-emitting under an {@code AmendCoordinate}'s slug, the durable
   * edge reconstructing a fetched case). The cellar stamps the trail afterwards via {@link
   * #withTrail}.
   */
  public SeedEnvelope(String domain, String coordinate, String payload) {
    this(domain, coordinate, payload, Trail.empty());
  }

  /**
   * This envelope with its provenance {@code trail} stamped — payload and routing keys unchanged.
   */
  public SeedEnvelope withTrail(Trail trail) {
    return new SeedEnvelope(domain, coordinate, payload, trail);
  }
}
