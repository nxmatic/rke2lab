package io.nxmatic.rke2lab.doctor.records;

/**
 * A predicate that holds when a given symptom no longer appears at the next visit — i.e., the
 * symptom resolved. The simplest expectation: "after we apply this prescription, the symptom should
 * be gone."
 *
 * <p>Serializes (via {@link ExpectationPredicate}'s polymorphism) as {@code {"kind":"resolution",
 * "symptom":"<slug>"}} — the {@code kind} discriminator is emitted by the base type, the {@code
 * symptom} by the annotated {@link Symptom}.
 */
public record ResolutionPredicate(Symptom symptom) implements ExpectationPredicate {

  @Override
  public boolean heldAt(Visit nextVisit) {
    return !nextVisit.symptomsRaised().contains(symptom);
  }
}
