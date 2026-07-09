package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.doctor.internal.DefaultReadinessAuthority;
import io.nxmatic.rke2lab.doctor.records.Action;
import io.nxmatic.rke2lab.doctor.records.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.records.ReadinessCheckpoint;
import io.nxmatic.rke2lab.doctor.records.ReadinessVerdict;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReadinessAuthorityTest {

  private static final SeedCodec CODEC = new SeedCodec();
  private final SeedHandler authority = new DefaultReadinessAuthority();

  private static SeedEnvelope checkpoint(String scenarioId, boolean failed, String override) {
    final ReadinessCheckpoint payload =
        new ReadinessCheckpoint(
            scenarioId,
            Optional.of(failed),
            Optional.ofNullable(override),
            Optional.empty(),
            List.of());
    return SeedEnvelope.of(DoctorCoordinate.READINESS_CHECKPOINT, CODEC.encode(payload));
  }

  private static Action action(SeedEnvelope verdict) {
    return CODEC.decode(verdict, ReadinessVerdict.class).action();
  }

  @Test
  void intrinsicWarningContinuesDegraded() {
    final SeedEnvelope verdict = authority.handle(checkpoint("systemd-adapter", true, null));
    assertEquals(DoctorCoordinate.READINESS_VERDICT.slug(), verdict.coordinate());
    assertEquals(Action.CONTINUE_DEGRADED, action(verdict));
  }

  @Test
  void operatorCriticalOverrideStops() {
    final SeedEnvelope verdict = authority.handle(checkpoint("systemd-adapter", true, "critical"));
    assertEquals(Action.STOP, action(verdict));
  }

  @Test
  void operatorWarningOverrideContinuesDegraded() {
    final SeedEnvelope verdict = authority.handle(checkpoint("systemd-adapter", true, "warning"));
    assertEquals(Action.CONTINUE_DEGRADED, action(verdict));
  }
}
