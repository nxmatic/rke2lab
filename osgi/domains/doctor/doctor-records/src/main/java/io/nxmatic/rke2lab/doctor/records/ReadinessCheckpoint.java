package io.nxmatic.rke2lab.doctor.records;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The wire contract for the {@code readiness-checkpoint} {@code SeedEnvelope}: what a checkpoint
 * reports about a scenario. Two producer concerns union into this one shape (both are the same
 * checkpoint object, so one coordinate, one schema):
 *
 * <ul>
 *   <li>the VERDICT checkpoint (host → the {@code readiness-verdict} SeedHandler): {@code
 *       scenarioId} plus {@code failed} and an operator {@code override?} — the authority reads
 *       only these;
 *   <li>the CONSULT checkpoint (host → the doctor's {@code consult}): {@code scenarioId}, a {@code
 *       recordedAt?} run instant, and the captured {@code observations} the doctor routes on.
 * </ul>
 *
 * Every field beyond {@code scenarioId} is optional because a given producer fills only its
 * concern; the record's components ARE the wire shape (nesting {@link ObservationWire}). Each realm
 * maps it ↔ {@code String} via {@code SeedCodec}.
 */
@SeedContract("readiness-checkpoint")
public record ReadinessCheckpoint(
    String scenarioId,
    Optional<Boolean> failed,
    Optional<String> override,
    Optional<Instant> recordedAt,
    List<ObservationWire> observations) {

  public ReadinessCheckpoint {
    failed = failed == null ? Optional.empty() : failed;
    override = override == null ? Optional.empty() : override;
    recordedAt = recordedAt == null ? Optional.empty() : recordedAt;
    observations = observations == null ? List.of() : List.copyOf(observations);
  }
}
