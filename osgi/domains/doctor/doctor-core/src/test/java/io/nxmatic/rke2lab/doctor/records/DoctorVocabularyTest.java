package io.nxmatic.rke2lab.doctor.records;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the doctor domain's seed vocabulary — its coordinates, actions, and symptom kinds. The slugs
 * cross the wire as strings in a {@code SeedEnvelope}, so we lock them down: a refactor cannot
 * drift the value without breaking the test. This vocabulary is doctor's own (moved out of the
 * neutral seam when the broker was made domain-agnostic), so it is pinned here in the doctor
 * domain.
 */
class DoctorVocabularyTest {

  @Test
  void coordinateSlugsAndDomainArePinned() {
    assertEquals("readiness-checkpoint", DoctorCoordinate.READINESS_CHECKPOINT.slug());
    assertEquals("readiness-verdict", DoctorCoordinate.READINESS_VERDICT.slug());
    assertEquals("consultation", DoctorCoordinate.CONSULTATION.slug());
    assertEquals("intervention-request", DoctorCoordinate.INTERVENTION_REQUEST.slug());
    assertEquals("intervention", DoctorCoordinate.INTERVENTION.slug());
    assertEquals("visit", DoctorCoordinate.VISIT.slug());
    // Every coordinate answers its owning domain — no central Domain enum.
    for (DoctorCoordinate coordinate : DoctorCoordinate.values()) {
      assertEquals("doctor", coordinate.domain());
    }
  }

  @Test
  void actionSlugsArePinnedAndRoundTrip() {
    assertEquals("stop", Action.STOP.slug());
    assertEquals("continue-degraded", Action.CONTINUE_DEGRADED.slug());
    assertEquals(Optional.of(Action.STOP), Action.parse("stop"));
    assertEquals(Optional.of(Action.CONTINUE_DEGRADED), Action.parse("continue-degraded"));
    assertEquals(Optional.empty(), Action.parse("nope"));
    assertEquals(Optional.empty(), Action.parse(""));
    assertEquals(Optional.empty(), Action.parse("   "));
  }

  @Test
  void symptomKindSlugsArePinnedAndRoundTrip() {
    assertEquals("connection-refused", SymptomKind.CONNECTION_REFUSED.slug());
    assertEquals("timeout", SymptomKind.TIMEOUT.slug());
    assertEquals("kubeconfig-missing", SymptomKind.KUBECONFIG_MISSING.slug());
    assertEquals("api-not-ready", SymptomKind.API_NOT_READY.slug());
    assertEquals("controller-not-ready", SymptomKind.CONTROLLER_NOT_READY.slug());
    assertEquals("reservation-refused", SymptomKind.RESERVATION_REFUSED.slug());
    assertEquals(Optional.of(SymptomKind.TIMEOUT), SymptomKind.parse("timeout"));
    assertEquals(Optional.of(SymptomKind.API_NOT_READY), SymptomKind.parse("api-not-ready"));
    assertEquals(Optional.empty(), SymptomKind.parse("nope"));
    assertEquals(Optional.empty(), SymptomKind.parse(""));
    assertEquals(Optional.empty(), SymptomKind.parse("   "));
  }
}
