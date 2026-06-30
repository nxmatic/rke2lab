package io.nxmatic.rke2lab.world.gateway.port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DocumentTest {

  @Test
  void carriesDomainCoordinateAndStringPayload() {
    // The payload is a serialized JSON String — the seam carries only flat (JDK) types, never a
    // jackson JsonNode, so world-gateway has no jackson dependency at all. Each world parses the
    // String with its own jackson.
    final String payload =
        "{\"" + WorldGatewayCatalog.FIELD_ACTION + "\":\"" + Action.STOP.slug() + "\"}";
    final Document doc =
        new Document(Domain.DOCTOR.slug(), Coordinate.READINESS_VERDICT.slug(), payload);

    assertEquals(Domain.DOCTOR.slug(), doc.domain());
    assertEquals(Coordinate.READINESS_VERDICT.slug(), doc.coordinate());
    assertEquals(payload, doc.payload());
  }

  @Test
  void catalogConstantsAreTheCanonicalStrings() {
    // The single source of truth for field keys (the schema) — call sites must reference these,
    // never literals. Closed value domains (domain, coordinate, action, symptom kind) are now
    // typed enums and tested in GatewayVocabularyTest.
    assertEquals("scenarioId", WorldGatewayCatalog.FIELD_SCENARIO_ID);
    assertEquals("failed", WorldGatewayCatalog.FIELD_FAILED);
    assertEquals("override", WorldGatewayCatalog.FIELD_OVERRIDE);
    assertEquals("action", WorldGatewayCatalog.FIELD_ACTION);
    assertEquals("reason", WorldGatewayCatalog.FIELD_REASON);
  }

  @Test
  void consultationFieldsArePinned() {
    assertEquals("narration", WorldGatewayCatalog.FIELD_NARRATION);
    assertEquals("diagnosisAdoc", WorldGatewayCatalog.FIELD_DIAGNOSIS_ADOC);
    assertEquals("observations", WorldGatewayCatalog.FIELD_OBSERVATIONS);
    assertEquals("recordedAt", WorldGatewayCatalog.FIELD_RECORDED_AT);
  }
}
