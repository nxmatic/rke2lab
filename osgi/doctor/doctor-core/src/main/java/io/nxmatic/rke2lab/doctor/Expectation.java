package io.nxmatic.rke2lab.doctor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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

  /**
   * Flat map view for persistence. The {@code predicate} is itself a flat map (dispatched via its
   * {@code kind} discriminator), so this structure is fully serializable.
   */
  public Map<String, Object> toOutputMap() {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put("problem", problem.toRef());
    map.put("fromPrescription", fromPrescription.id());
    map.put("predicate", predicate.toOutputMap());
    map.put("recordedAt", recordedAt.toString());
    return Map.copyOf(map);
  }
}
