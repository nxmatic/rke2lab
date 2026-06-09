package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.List;

/**
 * The symptoms that tend to appear alongside a given one within the same visit — a comorbidity view
 * suggesting a shared root cause rather than independent faults.
 */
public record SymptomCorrelation(Symptom symptom, List<Symptom> cooccurring) {

  public SymptomCorrelation {
    cooccurring = cooccurring == null ? List.of() : List.copyOf(cooccurring);
  }
}
