package io.seedmatic.rke2lab.seed.broker.port;

/**
 * One link of a {@link Trail} — a self-describing coordinate that names WHERE a link sits: its
 * {@code domain} and {@code coordinate}. Two shapes descend from it, one mechanism (§
 * fil-d-ariane):
 *
 * <ul>
 *   <li>{@link SourceCrumb} — the CELLAR's git-source crumb: a value's origin coordinate PLUS the
 *       git source it was cultivated from (the commit {@code sha} + {@code dirty}). A value's
 *       lineage back to the commit it descends from.
 *   <li>{@link Crossing} — the SCENARIO's crossing crumb: the bare {@code (domain, coordinate)} a
 *       failure grew through, no git provenance. A failure's path back to the crossing it grew in.
 * </ul>
 *
 * <p>Sealed and flat (String + boolean fields on the records) so the whole trail rides the seam
 * inside a {@link SeedEnvelope} with no bundle-owned type — the git fields on {@link SourceCrumb}
 * are RAW strings, deliberately NOT a domain {@code Provenance} (the seam depends on no domain). No
 * jackson annotation lives here: the seam carries no third-party dependency, so the polymorphic
 * (de)serialization is configured once in {@code SeedCodec}, the sole codec each realm loads.
 */
public sealed interface Breadcrumb permits Crossing, SourceCrumb {

  /** The domain this link sits under. */
  String domain();

  /** The coordinate (slug) this link sits under. */
  String coordinate();
}
