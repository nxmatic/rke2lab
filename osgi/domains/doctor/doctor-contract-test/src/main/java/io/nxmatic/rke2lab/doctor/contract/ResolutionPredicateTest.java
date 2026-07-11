package io.nxmatic.rke2lab.doctor.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@link ResolutionPredicate} behavior — does the symptom-resolved prediction hold at the next
 * visit? The wire shape ({@code {"kind":"resolution","symptom":...}}) is now the codec's
 * polymorphic (de)serialization, covered by the round-trip tests in doctor-core; here we pin only
 * the domain predicate.
 */
class ResolutionPredicateTest {

  private static ConsultationReport report(Symptom symptom) {
    final List<ReferralReply> replies = List.of();
    final RemediationPlan plan = new RemediationPlan(symptom, replies, "summary");
    return new ConsultationReport("checkpoint-id", List.of(), plan);
  }

  private static Visit visitRaisingNothing() {
    return new Visit(1, Instant.ofEpochSecond(1), List.of(), List.of());
  }

  private static Visit visitRaising(Symptom symptom) {
    return new Visit(1, Instant.ofEpochSecond(1), List.of(report(symptom)), List.of());
  }

  @Test
  void heldAt_symptomResolved_returnsTrue() {
    ResolutionPredicate predicate = new ResolutionPredicate(Symptom.CONNECTION_REFUSED);
    Visit visit = visitRaisingNothing();

    boolean held = predicate.heldAt(visit);

    assertTrue(held);
  }

  @Test
  void heldAt_symptomStillRaised_returnsFalse() {
    ResolutionPredicate predicate = new ResolutionPredicate(Symptom.CONNECTION_REFUSED);
    Visit visit = visitRaising(Symptom.CONNECTION_REFUSED);

    boolean held = predicate.heldAt(visit);

    assertFalse(held);
  }

  @Test
  void heldAt_differentSymptomRaised_returnsTrue() {
    ResolutionPredicate predicate = new ResolutionPredicate(Symptom.CONNECTION_REFUSED);
    Visit visit = visitRaising(Symptom.TIMEOUT);

    boolean held = predicate.heldAt(visit);

    assertTrue(held);
  }
}
