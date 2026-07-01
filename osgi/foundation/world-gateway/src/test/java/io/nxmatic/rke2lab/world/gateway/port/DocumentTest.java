package io.nxmatic.rke2lab.world.gateway.port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DocumentTest {

  @Test
  void carriesDomainCoordinateAndStringPayload() {
    // The payload is a serialized JSON String — the seam carries only flat (JDK) types, never a
    // jackson JsonNode, so world-gateway has no jackson dependency at all. Each world parses the
    // String with its own jackson.
    final String payload = "{\"action\":\"" + Action.STOP.slug() + "\"}";
    final Document doc =
        new Document(Domain.DOCTOR.slug(), Coordinate.READINESS_VERDICT.slug(), payload);

    assertEquals(Domain.DOCTOR.slug(), doc.domain());
    assertEquals(Coordinate.READINESS_VERDICT.slug(), doc.coordinate());
    assertEquals(payload, doc.payload());
  }

  @Test
  void consultationFieldsArePinned() {
    // The remaining FIELD_* pin the consultation coordinate's keys (the last hand-written wire
    // shape, migrated in T9); the readiness-checkpoint/verdict/intervention coordinates are now
    // typed wire-records whose components ARE the schema (projected by SCHEMA_CONCORD).
    assertEquals("scenarioId", WorldGatewayCatalog.FIELD_SCENARIO_ID);
    assertEquals("narration", WorldGatewayCatalog.FIELD_NARRATION);
    assertEquals("diagnosisAdoc", WorldGatewayCatalog.FIELD_DIAGNOSIS_ADOC);
  }
}
