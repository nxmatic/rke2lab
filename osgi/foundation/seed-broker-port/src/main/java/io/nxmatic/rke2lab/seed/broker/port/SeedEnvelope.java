package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The neutral envelope every host↔OSGi crossing carries — the seed sown in and the plant reaped out
 * are both a {@code SeedEnvelope}: a document of a given type, owned by a domain, whose body is a
 * serialized JSON {@code String}. The host and OSGi share no data type — only this record and its
 * three {@code String} fields cross, all flat (JDK) types, so the seam references nothing a bundle
 * owns. Each world parses/serializes the {@code payload} with ITS OWN jackson (the host's flat one;
 * a bundle's own) — no jackson type ever crosses the boundary, which is what keeps a {@code
 * type=seam} pure (a {@code JsonNode} payload once leaked the jackson bundle across the flat seam
 * and caused a {@code LinkageError} in-container).
 *
 * <p>{@code coordinate} is the document type and the schema key; {@code domain} names the owner.
 * See docs/architecture/osgi/seed-broker-spec.adoc.
 */
public record SeedEnvelope(String domain, String coordinate, String payload) {

  /**
   * Wrap a payload under a {@link SeedCoordinate} — the single-source factory: the coordinate
   * carries BOTH the domain and the slug, so a call site names only the coordinate and the payload,
   * never restating the two wire fields (the redundancy the removed central {@code Domain} enum
   * used to invite).
   */
  public static SeedEnvelope of(SeedCoordinate coordinate, String payload) {
    return new SeedEnvelope(coordinate.domain(), coordinate.slug(), payload);
  }
}
