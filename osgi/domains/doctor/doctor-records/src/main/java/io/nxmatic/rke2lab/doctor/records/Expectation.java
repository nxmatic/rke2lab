package io.nxmatic.rke2lab.doctor.records;

import java.time.Instant;

/**
 * One expectation about what a prescription predicts will be true at the next visit. When a
 * prescription is written for a symptom, it records an {@link ExpectationPredicate} — normally a
 * {@link ResolutionPredicate} (the symptom should be gone). The expectation is later checked
 * against the next visit to confirm whether the prescription worked. This is the bridge between a
 * prescription's intent and the operator's intervention that (hopefully) fulfilled it.
 */
public record Expectation(
    ProblemRef problem,
    RemediationProgramRef fromPrescription,
    ExpectationPredicate predicate,
    Instant recordedAt) {

  public Expectation {
    if (problem == null) {
      throw new IllegalArgumentException("problem cannot be null");
    }
    if (fromPrescription == null) {
      throw new IllegalArgumentException("fromPrescription cannot be null");
    }
    if (predicate == null) {
      throw new IllegalArgumentException("predicate cannot be null");
    }
    if (recordedAt == null) {
      throw new IllegalArgumentException("recordedAt cannot be null");
    }
  }

  /**
   * The Pulumi output key under which a list of expectations is persisted — mirrors {@link
   * ConsultationReport#OUTPUT_KEY}. Each checkpoint that writes a prescription can append its
   * expectation to this list, allowing the next visit to verify whether the intervention succeeded.
   */
  public static final String OUTPUT_KEY = "expectations";

  /**
   * Convenience accessor for the symptom. An expectation's problem always names a symptom (it
   * opened from an observed one).
   */
  public Symptom symptom() {
    return problem.symptom().orElseThrow();
  }
}
