package io.nxmatic.rke2lab.world.gateway.port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the exchange vocabulary's closed domains — coordinates, actions, and symptom kinds. The
 * slugs cross the wire as strings in {@link Document}, so we lock them down: a refactor cannot
 * drift the value without breaking the test.
 */
class ExchangeVocabularyTest {

  @Test
  void coordinateSlugsArePinnedAndRoundTrip() {
    assertEquals("readiness-checkpoint", Coordinate.READINESS_CHECKPOINT.slug());
    assertEquals("readiness-verdict", Coordinate.READINESS_VERDICT.slug());
    assertEquals("consultation", Coordinate.CONSULTATION.slug());
    assertEquals(Optional.of(Coordinate.CONSULTATION), Coordinate.parse("consultation"));
    assertEquals(Optional.empty(), Coordinate.parse("nope"));
    assertEquals(Optional.empty(), Coordinate.parse(null));
    assertEquals(Optional.empty(), Coordinate.parse(""));
    assertEquals(Optional.empty(), Coordinate.parse("   "));
  }

  @Test
  void actionSlugsArePinnedAndRoundTrip() {
    assertEquals("stop", Action.STOP.slug());
    assertEquals("continue-degraded", Action.CONTINUE_DEGRADED.slug());
    assertEquals(Optional.of(Action.STOP), Action.parse("stop"));
    assertEquals(Optional.of(Action.CONTINUE_DEGRADED), Action.parse("continue-degraded"));
    assertEquals(Optional.empty(), Action.parse("nope"));
    assertEquals(Optional.empty(), Action.parse(null));
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
    assertEquals(Optional.of(SymptomKind.TIMEOUT), SymptomKind.parse("timeout"));
    assertEquals(Optional.of(SymptomKind.API_NOT_READY), SymptomKind.parse("api-not-ready"));
    assertEquals(Optional.empty(), SymptomKind.parse("nope"));
    assertEquals(Optional.empty(), SymptomKind.parse(null));
    assertEquals(Optional.empty(), SymptomKind.parse(""));
    assertEquals(Optional.empty(), SymptomKind.parse("   "));
  }

  @Test
  void domainSlugsArePinnedAndRoundTrip() {
    assertEquals("doctor", Domain.DOCTOR.slug());
    assertEquals(Optional.of(Domain.DOCTOR), Domain.parse("doctor"));
    assertEquals(Optional.empty(), Domain.parse("nope"));
    assertEquals(Optional.empty(), Domain.parse(null));
    assertEquals(Optional.empty(), Domain.parse(""));
    assertEquals(Optional.empty(), Domain.parse("   "));
  }
}
