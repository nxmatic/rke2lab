package io.nxmatic.rke2lab.seed.broker.port;

/**
 * A GARDENER'S coordinate — a generic verb the gardener sows toward ANY soil, parameterised by the
 * soil name alone. The closed counterpart of a domain's own coordinates, and the distinction is the
 * heart of the model's decorrelation:
 *
 * <ul>
 *   <li>a *domain* coordinate is an enum constant ({@code DoctorCoordinate.CONSULTATION}) — the
 *       DOMAIN's vocabulary, routed by identity; naming one means naming a domain type.
 *   <li>a *value* coordinate is a record parameterised by a {@code domain()} string ({@link
 *       SplitCoordinate}, {@link RunbookCoordinate}) — the GARDENER's vocabulary, routed by {@code
 *       equals}; the host constructs {@code new X(soil)} holding only a soil NAME, so it names no
 *       domain type. A domain contributes a handler serving {@code new X(itsDomain)}; two equal
 *       records route together ({@code DefaultSeedBroker} keys on {@code equals}).
 * </ul>
 *
 * <p>The interface is {@code sealed} on purpose: value coordinates ARE the broker's gardening
 * vocabulary, and that vocabulary is a CLOSED set. Adding a verb (a new way the gardener addresses
 * any soil) is a deliberate act — extend the {@code permits} list here — never an accident in a
 * domain. This is what lets the frontier stay pure gardening: everything the host may sow that is
 * NOT a domain's own coordinate is exactly one of these, enumerable and reviewed in one place.
 *
 * <p>Every implementer is a record (value semantics for {@code equals}-routing) carrying a single
 * {@code domain} soil name and a fixed per-verb {@code SLUG}. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ two kinds of coordinate).
 */
public sealed interface ValueCoordinate extends SeedCoordinate
    permits SplitCoordinate, RunbookCoordinate {}
