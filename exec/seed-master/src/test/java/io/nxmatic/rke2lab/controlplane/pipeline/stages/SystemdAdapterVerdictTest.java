package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.exchange.port.Action;
import io.nxmatic.rke2lab.exchange.port.Coordinate;
import io.nxmatic.rke2lab.exchange.port.Document;
import io.nxmatic.rke2lab.exchange.port.Domain;
import io.nxmatic.rke2lab.exchange.port.ExchangeCatalog;
import io.nxmatic.rke2lab.exchange.port.ReadinessAuthority;
import io.nxmatic.rke2lab.pipeline.TopicFailure;
import org.junit.jupiter.api.Test;

class SystemdAdapterVerdictTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ReadinessAuthority authorityReturning(String action) {
    return checkpoint -> {
      final ObjectNode verdict = MAPPER.createObjectNode();
      verdict.put(ExchangeCatalog.FIELD_ACTION, action);
      verdict.put(ExchangeCatalog.FIELD_REASON, "test");
      return new Document(Domain.DOCTOR.slug(), Coordinate.READINESS_VERDICT.slug(), verdict);
    };
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
