package io.nxmatic.rke2lab.doctor.records;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A predicate that holds when a given symptom no longer appears at the next visit — i.e., the
 * symptom resolved. The simplest expectation: "after we apply this prescription, the symptom should
 * be gone."
 */
public record ResolutionPredicate(Symptom symptom) implements ExpectationPredicate {

  @Override
  public boolean heldAt(Visit nextVisit) {
    return !nextVisit.symptomsRaised().contains(symptom);
  }

  @Override
  public Map<String, Object> toOutputMap() {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put("kind", "resolution");
    map.put("symptom", symptom.id());
    return Map.copyOf(map);
  }

  /**
   * Reconstruct from a map. Expects {@code "symptom"} key with a parseable symptom id. Missing or
   * unparseable → empty.
   */
  static Optional<ResolutionPredicate> fromOutputMap(Map<String, Object> map) {
    final Object symptomRaw = map.get("symptom");
    if (!(symptomRaw instanceof String symptomId)) {
      return Optional.empty();
    }

    final Optional<Symptom> symptom = Symptom.parse(symptomId);
    return symptom.map(ResolutionPredicate::new);
  }
}
