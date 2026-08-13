package io.seedmatic.rke2lab.seed.broker.port;

/**
 * The neutral coordinate of one plot of provisioned ground — a Pulumi stack, addressed as {@code
 * (project, stack)}. It is the gardening word for "which stack", homed here in the neutral seam so
 * BOTH worlds name it: the host EDGE projects its {@code StackCoordinate(project, stack)} onto a
 * {@code Parcel} 1:1 (nothing invented), the doctor HANDLER projects its {@code Patient} onto the
 * SAME {@code Parcel} — neither side's word crosses, only the neutral parcel sits between them. It
 * is what a {@link Cellar} is addressed by (store/fetch a parcel's cellar), never a domain
 * identity.
 *
 * <p>{@code (project, stack)} is the REAL intersection of the two worlds — exactly what the host's
 * {@code StackCoordinate} carries. The doctor's {@code Patient} additionally has an {@code org},
 * but that is always the constant {@code "organization"} in this single-org system and the host has
 * no org to give; so {@code org} stays a doctor-side constant applied when the doctor projects
 * {@code Parcel → Patient}, never a field the neutral seam forces the host to invent.
 *
 * <p>Only flat (JDK) {@code String} fields cross, so the seam references nothing a bundle owns —
 * the same {@code type=seam} purity as {@link SeedEnvelope}. See
 * docs/architecture/osgi/seed-broker-spec.adoc (the Cellar section) and the gardening lexicon
 * (parcelle).
 */
public record Parcel(String project, String stack) {

  /** The project/stack path, for a human label or a log line — never a routing key. */
  public String qualifiedName() {
    return project + "/" + stack;
  }
}
