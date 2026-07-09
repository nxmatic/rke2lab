package io.nxmatic.rke2lab.seed.broker.port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DocumentTest {

  @Test
  void carriesDomainCoordinateAndStringPayload() {
    // The payload is a serialized JSON String — the seam carries only flat (JDK) types, never a
    // jackson JsonNode, so seed-broker-port has no jackson dependency at all. Each world parses the
    // String with its own jackson. The domain/coordinate slugs are plain Strings here: the concrete
    // coordinate enums are domain-owned (e.g. DoctorCoordinate), never referenced from this neutral
    // seam.
    final String payload = "{\"action\":\"stop\"}";
    final SeedEnvelope envelope = new SeedEnvelope("doctor", "readiness-verdict", payload);

    assertEquals("doctor", envelope.domain());
    assertEquals("readiness-verdict", envelope.coordinate());
    assertEquals(payload, envelope.payload());
  }
}
