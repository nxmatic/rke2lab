package io.nxmatic.rke2lab.seed.broker.port;

/**
 * A grower behind the {@link SeedBroker} door: the thing that actually sows one coordinate of seed.
 * Each handler declares the coordinate it {@link #serves} (its response type) and turns a request
 * {@link Document} into that response {@link Document}. The former per-crossing service interfaces
 * ({@code ReadinessAuthority#assess}, {@code InterventionIntake#canonicalize}) become handlers:
 * same {@code Document → Document} shape, now selected by coordinate rather than by their own type.
 *
 * <p>Handlers are published OSGi-side ({@code @Component}); the broker collects them by Declarative
 * Services and dispatches by {@link #serves}. A domain contributes a handler per coordinate it
 * grows; the host never sees them — it sees only the broker.
 */
public interface SeedHandler {

  /** The coordinate this handler grows — the {@code wanted} value that routes a seed here. */
  Coordinate serves();

  /** Sow the request {@code seed} and return the {@link #serves} coordinate's Document. */
  Document handle(Document seed);
}
