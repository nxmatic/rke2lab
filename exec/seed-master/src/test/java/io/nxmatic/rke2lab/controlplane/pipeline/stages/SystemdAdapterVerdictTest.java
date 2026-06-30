package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.pipeline.TopicFailure;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import org.junit.jupiter.api.Test;

class SystemdAdapterVerdictTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ReadinessAuthority authorityReturning(String action) {
    return checkpoint -> {
      final ObjectNode verdict = MAPPER.createObjectNode();
      verdict.put(WorldGatewayCatalog.FIELD_ACTION, action);
      verdict.put(WorldGatewayCatalog.FIELD_REASON, "test");
      return new Document(
          Domain.DOCTOR.slug(), Coordinate.READINESS_VERDICT.slug(), serialize(verdict));
    };
  }

  private static String serialize(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void stopVerdictThrowsTopicFailure() {
    final SystemdAdapterStage stage =
        SystemdAdapterStageFixture.failing(authorityReturning(Action.STOP.slug()));
    assertThrows(TopicFailure.class, stage::launch);
  }

  @Test
  void continueDegradedVerdictDoesNotThrow() {
    final SystemdAdapterStage stage =
        SystemdAdapterStageFixture.failing(authorityReturning(Action.CONTINUE_DEGRADED.slug()));
    assertDoesNotThrow(stage::launch);
  }
}
