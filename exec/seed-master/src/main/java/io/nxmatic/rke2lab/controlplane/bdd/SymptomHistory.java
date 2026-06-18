package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.List;

/**
 * The longitudinal view of one symptom across the record: every {@link Occurrence} (the visit
 * {@code version} and the {@code checkpointId} that raised it). A symptom seen more than once is
 * chronic — recurring rather than a one-off acute episode.
 */
public record SymptomHistory(Symptom symptom, List<Occurrence> occurrences) {

  public record Occurrence(int version, String checkpointId) {}

  public SymptomHistory {
    occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
  }

  public int count() {
    return occurrences.size();
  }

  public boolean isChronic() {
    return occurrences.size() > 1;
  }
}
