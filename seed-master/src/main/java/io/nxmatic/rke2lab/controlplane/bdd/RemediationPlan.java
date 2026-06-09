package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Generalist's synthesis: the symptom that was diagnosed, the prescriptions the consulted
 * specialists wrote, and a one-line generalist summary (its global view). Flows into the runbook
 * node's Mitigation section and the inline log. An empty prescription list is a valid plan — the
 * generalist saw the symptom but no specialist had a treatment to offer.
 */
public record RemediationPlan(
    Symptom symptom, List<Prescription> prescriptions, String generalistSummary) {

  public RemediationPlan {
    prescriptions = prescriptions == null ? List.of() : List.copyOf(prescriptions);
  }

  public boolean hasPrescriptions() {
    return !prescriptions.isEmpty();
  }

  /** The first prescription, if any — convenience for single-treatment cases. */
  public Optional<Prescription> primaryPrescription() {
    return prescriptions.isEmpty() ? Optional.empty() : Optional.of(prescriptions.get(0));
  }

  /** Flat map view; {@code symptom} is the kebab id, prescriptions are nested flat maps. */
  public Map<String, Object> toOutputMap() {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put(Symptom.ENVELOPE_KEY, symptom.id());
    map.put("generalistSummary", generalistSummary);
    map.put("prescriptions", prescriptions.stream().map(Prescription::toOutputMap).toList());
    return map;
  }
}
