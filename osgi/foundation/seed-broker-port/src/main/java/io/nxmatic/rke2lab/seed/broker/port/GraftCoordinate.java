package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The introspection meta-coordinate: "given a seed of your domain, hand me its GRAFTS" (the named
 * sub-trees the frontier files verbatim). Unlike a domain's own coordinates — enum constants routed
 * by identity — this is a VALUE coordinate: a domain contributes a reflector serving {@code new
 * GraftCoordinate(itsDomain)}, and the frontier, holding only an opaque {@link SeedEnvelope}, asks
 * with {@code new GraftCoordinate(seed.domain())} built from the envelope's OWN domain string. Two
 * equal records route to the same handler ({@code DefaultSeedBroker} keys on {@code equals}), so
 * the host names no domain's coordinate type and the meta-coordinate never collides across domains
 * — each domain answers grafts for ITSELF.
 *
 * <p>This is the "ask the broker" mechanism the opacity turn rests on: the reflection of {@link
 * Graft} components happens OSGi-side in the domain's reflector (its wire-record's class, its
 * realm), so neither the storage backend nor the wire-record class ever transpires to the host. The
 * reaped {@link SeedEnvelope} carries the grafts keyed by name; the frontier affixes each under
 * that name.
 */
public record GraftCoordinate(String domain) implements SeedCoordinate {

  /** The single wire slug of the introspection verb, across every domain. */
  public static final String SLUG = "grafts";

  @Override
  public String slug() {
    return SLUG;
  }
}
