package io.seedmatic.rke2lab.seed.broker.port;

/**
 * A grower behind the {@link SeedBroker} door: the thing that actually sows one coordinate of seed.
 * Each handler declares the coordinate it {@link #serves} (its response type) and turns a request
 * {@link SeedEnvelope} into that response {@link SeedEnvelope}. The former per-crossing service
 * interfaces ({@code ReadinessAuthority#assess}, {@code InterventionIntake#canonicalize}) become
 * handlers: same {@code SeedEnvelope → SeedEnvelope} shape, now selected by coordinate rather than
 * by their own type.
 *
 * <p>Handlers are published OSGi-side ({@code @Component}); the broker collects them by Declarative
 * Services and dispatches by {@link #serves}. A domain contributes a handler per coordinate it
 * grows; the host never sees them — it sees only the broker.
 */
public interface SeedHandler {

  /** The coordinate this handler grows — the {@code wanted} value that routes a seed here. */
  SeedCoordinate serves();

  /**
   * Sow the request {@code seed} with the ambient transaction {@code cellar} and return the {@link
   * #serves} coordinate's SeedEnvelope. {@code cellar} IS the transaction (§ cellar-transactional):
   * a {@code *RunbookHandler} flattens its {@code txId} + write-set into the in-container run (so
   * the scion inherits both); an introspection handler (a reflector) ignores it. Always present,
   * never null — the optionality lives inside it ({@code transactionId()} empty for a
   * non-transactional play).
   */
  SeedEnvelope handle(Cellar cellar, SeedEnvelope seed);
}
