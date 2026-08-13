package io.seedmatic.rke2lab.seed.broker.port;

/**
 * The introspection meta-coordinate AMONT that RECONCILES: "fill this coordinate's input by role" —
 * hand me a {@code {role → value}} map keyed by neutral {@link Amendment} roles and I return the
 * wire-record's payload with each value bound to the field its role designates. The reconciling
 * twin of {@link ShapeCoordinate}: Shape DESCRIBES the input's form (its JSON Schema), Amend FILLS
 * it by role. Both are amont (before a runbook is sown), both reflect OSGi-side where the
 * wire-record's class lives; the caller holds no domain class and names no field.
 *
 * <p>This is where the broker earns its role as the door that RECONCILES two points of view: a
 * sower (e.g. the incus scion) holds a value under a NEUTRAL role ({@code soil}) and does not know
 * the target domain's field name ({@code materializationRoot}); it sows {@code new
 * AmendCoordinate(targetSoil)} with {@code {role → value}}, and the domain's amend reflector (which
 * owns the wire-record class) binds each role onto its {@code @Amendment}-marked component. So the
 * vocabulary reconciliation lives at the DOOR, not in the target's runbook handler — the handler
 * receives an already-reconciled input, never negotiates.
 *
 * <p>Like {@link ShapeCoordinate} and {@link SplitCoordinate}, a VALUE coordinate: the caller holds
 * only a soil name and asks {@code new AmendCoordinate(soil)}; a domain contributes a reflector
 * serving {@code new AmendCoordinate(itsDomain)}, keyed on {@code equals}. The requested coordinate
 * (which record to fill) travels in the seed's {@code coordinate()} field, the same envelope shape
 * Shape/Split use. See docs/architecture/osgi/seed-broker-spec.adoc (§ @Amendment).
 */
public record AmendCoordinate(String domain) implements ValueCoordinate {

  /** The single wire slug of the fill-by-role verb, across every domain. */
  public static final String SLUG = "amend";

  @Override
  public String slug() {
    return SLUG;
  }
}
