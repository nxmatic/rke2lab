package io.seedmatic.rke2lab.seed.broker.port;

/**
 * The introspection meta-coordinate AMONT: "describe the payload a coordinate expects" — hand me
 * the JSON Schema of the wire-record a given coordinate is the {@link SeedContract} for. The
 * symmetric twin of {@link SplitCoordinate}: Split reflects a reaped envelope's {@code @Scion}
 * components AVAL (after reaping — what did I get?), Shape projects an input wire-record's schema
 * AMONT (before sowing — what must I give?). Same reflection level, same mechanism (a value
 * coordinate + a domain reflector that holds the wire-record class + an opaque String reaped).
 *
 * <p>Like {@link SplitCoordinate}, a VALUE coordinate rather than a per-domain enum constant: the
 * host holds only a soil name and asks {@code new ShapeCoordinate(soil)}; a domain contributes a
 * reflector serving {@code new ShapeCoordinate(itsDomain)}, projecting the schema of the
 * wire-record its {@code @SeedContract(coordinate)} names. The requested coordinate travels in the
 * seed's {@code coordinate()} field (the same envelope shape Split uses to pick its bearer), so a
 * single reflector can describe every coordinate its domain owns. Two equal records route to the
 * same handler ({@code DefaultSeedBroker} keys on {@code equals}), so the host names no domain type
 * and reaps only an opaque JSON-Schema {@code String}.
 *
 * <p>This is the "ask the broker how to talk" verb — the OpenAPI {@code OPTIONS} of the seed door:
 * a sower learns a payload's shape FROM THE DOOR instead of compiling the wire-record class, so the
 * shared class stops being a parallel source of truth. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ introspection is the missing REST verb).
 */
public record ShapeCoordinate(String domain) implements ValueCoordinate {

  /** The single wire slug of the describe-shape verb, across every domain. */
  public static final String SLUG = "shape";

  @Override
  public String slug() {
    return SLUG;
  }
}
