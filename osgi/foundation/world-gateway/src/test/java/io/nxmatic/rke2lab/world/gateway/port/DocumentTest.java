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
  void pulumiTransportKeysArePinned() {
    // Every coordinate's wire shape is now a typed wire-record (its components ARE the schema,
    // projected by SCHEMA_CONCORD). The only FIELD_* left are the two Pulumi OUTPUT KEYS under
    // which
    // the doctor's opaque sub-trees round-trip through host state — pinned so a producer and the
    // reconstruction cannot drift apart.
    assertEquals("consultationReport", WorldGatewayCatalog.FIELD_CONSULTATION_REPORT);
    assertEquals("expectations", WorldGatewayCatalog.FIELD_EXPECTATIONS);
  }
}
