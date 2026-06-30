package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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

  @Test
  void toOutputMap_containsKindAndSymptom() {
    ResolutionPredicate predicate = new ResolutionPredicate(Symptom.CONNECTION_REFUSED);

    Map<String, Object> map = predicate.toOutputMap();

    assertEquals("resolution", map.get("kind"));
    assertEquals("connection-refused", map.get("symptom"));
  }

  @Test
  void fromOutputMap_roundTrip_preservesValue() {
    ResolutionPredicate original = new ResolutionPredicate(Symptom.TIMEOUT);

    Optional<ExpectationPredicate> reconstructed =
        ExpectationPredicate.fromOutputMap(original.toOutputMap());

    assertTrue(reconstructed.isPresent());
    assertEquals(original, reconstructed.get());
  }

  @Test
  void fromOutputMap_unknownKind_returnsEmpty() {
    Map<String, Object> map = Map.of("kind", "unknown-kind");

    Optional<ExpectationPredicate> result = ExpectationPredicate.fromOutputMap(map);

    assertTrue(result.isEmpty());
  }

  @Test
  void fromOutputMap_nullInput_returnsEmpty() {
    Optional<ExpectationPredicate> result = ExpectationPredicate.fromOutputMap(null);

    assertTrue(result.isEmpty());
  }

  @Test
  void fromOutputMap_nonMapInput_returnsEmpty() {
    Optional<ExpectationPredicate> result = ExpectationPredicate.fromOutputMap("not-a-map");

    assertTrue(result.isEmpty());
  }

  @Test
  void fromOutputMap_missingKind_returnsEmpty() {
    Map<String, Object> map = Map.of("symptom", "timeout");

    Optional<ExpectationPredicate> result = ExpectationPredicate.fromOutputMap(map);

    assertTrue(result.isEmpty());
  }

  @Test
  void fromOutputMap_missingSymptom_returnsEmpty() {
    Map<String, Object> map = Map.of("kind", "resolution");

    Optional<ExpectationPredicate> result = ExpectationPredicate.fromOutputMap(map);

    assertTrue(result.isEmpty());
  }

  @Test
  void fromOutputMap_unparseableSymptom_returnsEmpty() {
    Map<String, Object> map = Map.of("kind", "resolution", "symptom", "unknown-symptom");

    Optional<ExpectationPredicate> result = ExpectationPredicate.fromOutputMap(map);

    assertTrue(result.isEmpty());
  }
}
