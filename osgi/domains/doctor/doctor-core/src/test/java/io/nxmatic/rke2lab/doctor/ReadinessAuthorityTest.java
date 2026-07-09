package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.doctor.internal.DefaultReadinessAuthority;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessCheckpoint;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import io.nxmatic.rke2lab.world.gateway.port.SeedHandler;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReadinessAuthorityTest {

  private static final DocumentCodec CODEC = new DocumentCodec();
  private final SeedHandler authority = new DefaultReadinessAuthority();

  private static Document checkpoint(String scenarioId, boolean failed, String override) {
    final ReadinessCheckpoint payload =
        new ReadinessCheckpoint(
            scenarioId,
            Optional.of(failed),
            Optional.ofNullable(override),
            Optional.empty(),
            List.of());
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), CODEC.encode(payload));
  }

  private static Action action(Document verdict) {
    return CODEC.decode(verdict, ReadinessVerdict.class).action();
  }

  @Test
  void intrinsicWarningContinuesDegraded() {
    final Document verdict = authority.handle(checkpoint("systemd-adapter", true, null));
    assertEquals(Coordinate.READINESS_VERDICT.slug(), verdict.coordinate());
    assertEquals(Action.CONTINUE_DEGRADED, action(verdict));
  }

  @Test
  void operatorCriticalOverrideStops() {
    final Document verdict = authority.handle(checkpoint("systemd-adapter", true, "critical"));
    assertEquals(Action.STOP, action(verdict));
  }

  @Test
  void operatorWarningOverrideContinuesDegraded() {
    final Document verdict = authority.handle(checkpoint("systemd-adapter", true, "warning"));
    assertEquals(Action.CONTINUE_DEGRADED, action(verdict));
  }
}
