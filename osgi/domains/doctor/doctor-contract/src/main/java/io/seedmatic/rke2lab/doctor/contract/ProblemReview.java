package io.seedmatic.rke2lab.doctor.contract;

import java.util.Objects;

/**
 * The transient follow-up consultation request handed to {@link DriftSpecialist#review}. It is
 * never serialized (like {@link Referral}): it exists only for the duration of one review, bundling
 * everything the drift specialist needs to decide WHO resolved a problem whose prescription's
 * {@link Expectation} now holds.
 *
 * <p>The join window the specialist queries is {@code (priorVisit.when(), nextVisit.when()]} — the
 * prior visit exclusive, the next visit inclusive — matching {@link
 * InterventionLedger#between(java.time.Instant, java.time.Instant)}.
 */
public record ProblemReview(
    ProblemRef problem,
    Expectation expectation,
    Visit priorVisit,
    Visit nextVisit,
    InterventionLedger ledger) {

  public ProblemReview {
    Objects.requireNonNull(problem, "problem");
    Objects.requireNonNull(expectation, "expectation");
    Objects.requireNonNull(priorVisit, "priorVisit");
    Objects.requireNonNull(nextVisit, "nextVisit");
    Objects.requireNonNull(ledger, "ledger");
  }
}
