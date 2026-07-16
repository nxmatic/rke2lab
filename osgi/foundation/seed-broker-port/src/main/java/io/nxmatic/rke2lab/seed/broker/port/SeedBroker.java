package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The one door every seed crosses: a client hands a seed (a request {@link SeedEnvelope}) and names
 * the coordinate it wants to reap ({@code wanted} — the response type), and the broker sows it at
 * the right place and returns the reaped {@link SeedEnvelope}. The single verb subsumes the former
 * N service interfaces (one per crossing): the caller no longer resolves {@code ReadinessAuthority}
 * then {@code InterventionIntake} etc., it resolves ONE {@code SeedBroker} and routes by {@code
 * wanted}.
 *
 * <p>Routing is by the WANTED coordinate, not the seed's: two seeds of the same request type can
 * reap different responses (a {@code readiness-checkpoint} yields a {@code readiness-verdict} to
 * the authority, a {@code consultation} to the doctor), so the response coordinate is what selects
 * the handler. The world-crossing behind the door — same JVM today, remote tomorrow — is a broker
 * detail the client never sees. See docs/architecture/osgi/seed-broker-spec.adoc.
 */
public interface SeedBroker {

  /**
   * Sow {@code seed} with the ambient transaction {@code cellar} and reap the {@code wanted}
   * coordinate's {@link SeedEnvelope} — read as a sentence: <em>what I want, with what, the
   * seed</em>. Routes to the {@link SeedHandler} that {@link SeedHandler#serves serves} {@code
   * wanted}; throws {@link IllegalStateException} if no handler serves it (a coordinate with no
   * grower is a wiring bug, not a runtime condition).
   *
   * <p>{@code cellar} IS the run's transaction (§ cellar-transactional): it carries the {@code
   * txId} ({@code transactionId()}) AND the in-flight write-set, so a launched scion INHERITS both.
   * It is always present (every sow issues from a scenario whose cellar was injected), never null —
   * the optionality lives inside it ({@code transactionId()} is empty for a non-transactional
   * play). A {@code *RunbookHandler} flattens it at the launcher boundary; a reflector ignores it.
   */
  SeedEnvelope sow(SeedCoordinate wanted, Cellar cellar, SeedEnvelope seed);
}
