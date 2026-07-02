package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.nxmatic.rke2lab.pipeline.TopicFailure;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import org.junit.jupiter.api.Test;

class SystemdAdapterVerdictTest {

  private static final DocumentCodec CODEC = new DocumentCodec();

  private static ReadinessAuthority authorityReturning(Action action) {
    return checkpoint ->
        new Document(
            Domain.DOCTOR.slug(),
            Coordinate.READINESS_VERDICT.slug(),
            CODEC.encode(new ReadinessVerdict(action, "test")));
  }

  @Test
  void stopVerdictThrowsTopicFailure() {
    final SystemdAdapterTopic stage =
        SystemdAdapterTopicFixture.failing(authorityReturning(Action.STOP));
    assertThrows(TopicFailure.class, stage::launch);
  }

  @Test
  void continueDegradedVerdictDoesNotThrow() {
    final SystemdAdapterTopic stage =
        SystemdAdapterTopicFixture.failing(authorityReturning(Action.CONTINUE_DEGRADED));
    assertDoesNotThrow(stage::launch);
  }
}
