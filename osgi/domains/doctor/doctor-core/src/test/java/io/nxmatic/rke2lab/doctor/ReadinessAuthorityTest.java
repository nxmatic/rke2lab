package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.internal.DefaultReadinessAuthority;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import org.junit.jupiter.api.Test;

class ReadinessAuthorityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DocumentCodec CODEC = new DocumentCodec();
  private final ReadinessAuthority authority = new DefaultReadinessAuthority();

  private static Document checkpoint(String scenarioId, boolean failed, String override) {
    final ObjectNode payload = MAPPER.createObjectNode();
    payload.put(WorldGatewayCatalog.FIELD_SCENARIO_ID, scenarioId);
    payload.put(WorldGatewayCatalog.FIELD_FAILED, failed);
    if (override != null) {
      payload.put(WorldGatewayCatalog.FIELD_OVERRIDE, override);
    }
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), serialize(payload));
  }

  private static Action action(Document verdict) {
    return CODEC.decode(verdict.payload(), ReadinessVerdict.class).action();
  }

  private static String serialize(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void intrinsicWarningContinuesDegraded() {
    final Document verdict = authority.assess(checkpoint("systemd-adapter", true, null));
    assertEquals(Coordinate.READINESS_VERDICT.slug(), verdict.coordinate());
    assertEquals(Action.CONTINUE_DEGRADED, action(verdict));
  }

  @Test
  void operatorCriticalOverrideStops() {
    final Document verdict = authority.assess(checkpoint("systemd-adapter", true, "critical"));
    assertEquals(Action.STOP, action(verdict));
  }

  @Test
  void operatorWarningOverrideContinuesDegraded() {
    final Document verdict = authority.assess(checkpoint("systemd-adapter", true, "warning"));
    assertEquals(Action.CONTINUE_DEGRADED, action(verdict));
  }
}
