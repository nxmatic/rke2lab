package io.nxmatic.rke2lab.seed.broker.port;

import java.util.Optional;

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
   * Sow {@code seed} under transaction {@code txId} and reap the {@code wanted} coordinate's {@link
   * SeedEnvelope}. Routes to the {@link SeedHandler} that {@link SeedHandler#serves serves} {@code
   * wanted}; throws {@link IllegalStateException} if no handler serves it (a coordinate with no
   * grower is a wiring bug, not a runtime condition).
   *
   * <p>{@code txId} is the run's transaction id — the sow's crossing carries it so a launched scion
   * INHERITS its parent's transaction (§ cellar-transactional). It is {@link Optional#empty()} for
   * an UPSTREAM introspection sow ({@code AmendCoordinate}/{@code ShapeCoordinate}) that opens no
   * transactional scenario; present for a {@code RunbookCoordinate} sow that plays one
   * in-container.
   */
  SeedEnvelope sow(SeedCoordinate wanted, SeedEnvelope seed, Optional<String> txId);
}
