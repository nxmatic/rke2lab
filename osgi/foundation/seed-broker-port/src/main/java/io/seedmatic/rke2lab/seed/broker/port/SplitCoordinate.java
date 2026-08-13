package io.seedmatic.rke2lab.seed.broker.port;

/**
 * The introspection meta-coordinate: "split this seed's envelope into its addressable pieces" —
 * hand me the {@link Scion}s grouped under their {@link Rootstock}. Unlike a domain's own
 * coordinates — enum constants routed by identity — this is a VALUE coordinate: a domain
 * contributes a reflector serving {@code new SplitCoordinate(itsDomain)}, and the frontier, holding
 * only an opaque {@link SeedEnvelope}, asks with {@code new SplitCoordinate(seed.domain())} built
 * from the envelope's OWN domain string. Two equal records route to the same handler ({@code
 * DefaultSeedBroker} keys on {@code equals}), so the host names no domain's coordinate type and the
 * meta-coordinate never collides across domains — each domain splits envelopes for ITSELF.
 *
 * <p>This is the "ask the broker to split" mechanism the opacity turn rests on: the reflection of
 * {@link Scion}/{@link Rootstock} components happens OSGi-side in the domain's reflector (its
 * wire-record's class, its realm), so neither the storage backend nor the wire-record class ever
 * transpires to the host. The reaped {@link SeedEnvelope} carries the scions grouped under their
 * rootstock; the frontier nests each scion under its receiver, opaque.
 */
public record SplitCoordinate(String domain) implements ValueCoordinate {

  /** The single wire slug of the split verb, across every domain. */
  public static final String SLUG = "split";

  @Override
  public String slug() {
    return SLUG;
  }
}
