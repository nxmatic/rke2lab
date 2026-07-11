package io.nxmatic.rke2lab.doctor.contract;

import java.util.List;

/**
 * The symptoms that tend to appear alongside a given one within the same visit — a comorbidity view
 * suggesting a shared root cause rather than independent faults.
 */
public record Comorbidity(Symptom symptom, List<Symptom> cooccurring) {

  public Comorbidity {
    cooccurring = cooccurring == null ? List.of() : List.copyOf(cooccurring);
  }
}
