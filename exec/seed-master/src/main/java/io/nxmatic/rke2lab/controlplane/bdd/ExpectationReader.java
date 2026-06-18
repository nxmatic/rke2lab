package io.nxmatic.rke2lab.controlplane.bdd;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * The tolerant inverse of {@link Expectation#toOutputMap()}: rebuilds a typed {@link Expectation}
 * from the flat, string-keyed map persisted in a Pulumi output. It never throws — malformed input
 * yields {@link Optional#empty()} — so a stale or partially-written expectation degrades instead of
 * crashing the read.
 */
final class ExpectationReader {

  private ExpectationReader() {}

  /**
   * All four fields are HARD requirements: {@code problem} (must parse to a valid {@link
   * ProblemRef}), {@code fromPrescription} (must parse to a valid {@link RemediationProgramRef}),
   * {@code predicate} (must parse via {@link ExpectationPredicate#fromOutputMap}), and {@code
   * recordedAt} (must parse to an {@link Instant}). Any missing or unparseable field yields empty.
   */
  public static Optional<Expectation> fromOutputMap(Object raw) {
    if (!(raw instanceof Map<?, ?> uncheckedMap)) {
      return Optional.empty();
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> map = (Map<String, Object>) uncheckedMap;

    // problem is required
    final Object problemRaw = map.get("problem");
    if (!(problemRaw instanceof String problemRef)) {
      return Optional.empty();
    }
    final Optional<ProblemRef> problem = ProblemRef.parse(problemRef);
    if (problem.isEmpty()) {
      return Optional.empty();
    }

    // fromPrescription is required
    final Object fromPrescriptionRaw = map.get("fromPrescription");
    if (!(fromPrescriptionRaw instanceof String fromPrescriptionId)) {
      return Optional.empty();
    }
    final Optional<RemediationProgramRef> fromPrescription =
        RemediationProgramRef.parse(fromPrescriptionId);
    if (fromPrescription.isEmpty()) {
      return Optional.empty();
    }

    // predicate is required
    final Optional<ExpectationPredicate> predicate =
        ExpectationPredicate.fromOutputMap(map.get("predicate"));
    if (predicate.isEmpty()) {
      return Optional.empty();
    }

    // recordedAt is required
    final Object recordedAtRaw = map.get("recordedAt");
    if (!(recordedAtRaw instanceof String recordedAtString)) {
      return Optional.empty();
    }
    final Instant recordedAt;
    try {
      recordedAt = Instant.parse(recordedAtString);
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }

    return Optional.of(
        new Expectation(problem.get(), fromPrescription.get(), predicate.get(), recordedAt));
  }
}
