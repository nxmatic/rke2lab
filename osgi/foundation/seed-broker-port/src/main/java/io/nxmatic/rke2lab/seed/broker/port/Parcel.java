package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The neutral coordinate of one plot of provisioned ground — a Pulumi stack, addressed as {@code
 * (org, project, stack)}. It is the gardening word for "which stack", homed here in the neutral
 * seam so BOTH worlds name it: the host EDGE projects its {@code StackCoordinate} onto a {@code
 * Parcel}, the doctor HANDLER projects its {@code Patient} onto the SAME {@code Parcel} — neither
 * side's word crosses, only the neutral parcel sits between them. It is what a {@link Cellar} is
 * addressed by (store/fetch a parcel's cellar), never a domain identity.
 *
 * <p>Only flat (JDK) {@code String} fields cross, so the seam references nothing a bundle owns —
 * the same {@code type=seam} purity as {@link SeedEnvelope}. See
 * docs/architecture/osgi/seed-broker-spec.adoc (the Cellar section) and the gardening lexicon
 * (parcelle).
 */
public record Parcel(String org, String project, String stack) {

  /** The org/project/stack path, for a human label or a log line — never a routing key. */
  public String qualifiedName() {
    return org + "/" + project + "/" + stack;
  }
}
