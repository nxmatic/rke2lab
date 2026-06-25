package io.nxmatic.rke2lab.doctor.records;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The ledger of all {@link Intervention}s for a deployment: operator manual fixes, external drift
 * detection, or engine-applied prescriptions. Interventions are time-ordered, and the {@link
 * #between(Instant, Instant)} window query lets the drift detector find interventions that happened
 * between two visits.
 */
public record InterventionLedger(List<Intervention> interventions) {

  public InterventionLedger {
    // Order by WHEN: interventions are a time series, and the drift detector needs to find "what
    // happened between visit N and visit N+1" — which means querying by time, not arrival order.
    // Mirrors MedicalRecord's time-ordering rationale. No version tiebreaker needed (Intervention
    // has no version).
    interventions =
        interventions == null
            ? List.of()
            : interventions.stream().sorted(Comparator.comparing(Intervention::when)).toList();
  }

  public static InterventionLedger empty() {
    return new InterventionLedger(List.of());
  }

  /**
   * Returns interventions whose {@code when} is strictly after {@code fromExclusive} AND on or
   * before {@code toInclusive}. Used by the drift detector to find interventions in the window
   * between two visits: the prior visit (exclusive) and the next visit (inclusive).
   */
  public List<Intervention> between(Instant fromExclusive, Instant toInclusive) {
    return interventions.stream()
        .filter(i -> i.when().isAfter(fromExclusive) && !i.when().isAfter(toInclusive))
        .toList();
  }
}
