package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.List;

/**
 * Whether the treatments prescribed for one symptom held. Each {@link Attempt} is a prescription
 * written at a given visit {@code version} and whether the symptom {@code recurred} at the next
 * visit. A treatment "ever worked" if at least one attempt was followed by no recurrence — and was
 * not {@code confounded}: a confounded attempt is one where the symptom resolved but a non-engine
 * intervention in the window explains it, so the resolution cannot be credited to the treatment.
 */
public record TreatmentEfficacy(Symptom symptom, List<Attempt> attempts) {

  public record Attempt(int version, String programRef, boolean recurred, boolean confounded) {}

  public TreatmentEfficacy {
    attempts = attempts == null ? List.of() : List.copyOf(attempts);
  }

  public boolean everWorked() {
    return attempts.stream().anyMatch(attempt -> !attempt.recurred() && !attempt.confounded());
  }
}
