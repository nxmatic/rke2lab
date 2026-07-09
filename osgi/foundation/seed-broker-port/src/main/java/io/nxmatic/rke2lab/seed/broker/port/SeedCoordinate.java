package io.nxmatic.rke2lab.seed.broker.port;

/**
 * A seed coordinate — the document type and schema key a {@link SeedEnvelope} carries, and the
 * routing key the broker dispatches on ({@code sow(SeedCoordinate wanted, …)}). A CONTRIBUTABLE
 * interface, exactly like {@link WireEnum}: the foundation seam owns the CONCEPT (a coordinate has
 * a {@code slug()} and belongs to a {@code domain()}), each domain contributes its own coordinates
 * as an enum {@code implements SeedCoordinate} (e.g. {@code DoctorCoordinate}, {@code
 * SeedClusterCoordinate}). So "publish a handler = publish its coordinates" holds end to end — the
 * seam names no domain's coordinate, and a new domain adds its own without editing the center.
 * There is no central {@code Domain} enum: a coordinate belongs to its domain by construction (it
 * is declared in that domain), so {@code domain()} is the coordinate's own answer, not a shared
 * registry.
 *
 * <p>{@code slug()} is the wire value placed in {@link SeedEnvelope#coordinate()}; {@code domain()}
 * the value in {@link SeedEnvelope#domain()}. The envelope stays neutral and never holds a concrete
 * coordinate type. Dispatch is by identity — {@code DefaultSeedBroker} indexes handlers in a {@code
 * Map<SeedCoordinate, SeedHandler>} keyed on the contributed enum constant — so a domain-owned enum
 * works unchanged.
 */
public interface SeedCoordinate {

  /** The kebab-case wire value placed in a {@link SeedEnvelope}'s coordinate field. */
  String slug();

  /** The owning domain's slug, placed in a {@link SeedEnvelope}'s domain field. */
  String domain();
}
