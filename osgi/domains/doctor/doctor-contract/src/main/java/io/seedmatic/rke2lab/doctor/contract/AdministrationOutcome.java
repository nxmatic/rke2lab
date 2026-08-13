package io.seedmatic.rke2lab.doctor.contract;

import java.util.Map;

/**
 * What a {@link Remediator} reports after administering a {@link Prescription} against the live
 * system: a closed-set {@link AdministrationStatus}, the structured {@link #payload} (what the
 * remediator did / observed), and a human {@link #summary}. The administering counterpart of a
 * specialist's {@link Assessment} — present whatever the status, so an administration is never
 * silent (a {@link AdministrationStatus#SKIPPED} keeps its reason, like an assessment-only reply).
 */
public record AdministrationOutcome(
    AdministrationStatus status, Map<String, Object> payload, String summary) {

  public AdministrationOutcome {
    if (status == null) {
      throw new IllegalArgumentException("status cannot be null");
    }
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("summary cannot be null or blank");
    }
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public static AdministrationOutcome of(
      AdministrationStatus status, Map<String, Object> payload, String summary) {
    return new AdministrationOutcome(status, payload, summary);
  }
}
