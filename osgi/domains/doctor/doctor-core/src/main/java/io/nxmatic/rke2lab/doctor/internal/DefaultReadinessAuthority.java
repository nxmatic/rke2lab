package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.contract.Action;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.nxmatic.rke2lab.doctor.contract.ReadinessVerdict;
import io.nxmatic.rke2lab.doctor.contract.Severity;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * The OSGi-side readiness authority: it owns the severity vocabulary the host no longer holds.
 * Given a checkpoint SeedEnvelope (scenario id, failed, optional operator override), it resolves
 * the effective severity — the operator override if present, else the scenario's intrinsic severity
 * — and maps it to a provisioning verdict ({@code stop} iff CRITICAL, else {@code
 * continue-degraded}). Published as a {@link SeedHandler} serving {@code readiness-verdict}, so the
 * broker routes a readiness checkpoint here when the host sows for a verdict; the flat host reads
 * only the verdict's action field.
 *
 * <p>The SeedEnvelope payload is a serialized JSON {@code String}; this authority parses and
 * serializes it with its OWN jackson (a doctor-core bundle dependency) — no jackson type crosses
 * the seam.
 */
@Component(service = SeedHandler.class)
public final class DefaultReadinessAuthority implements SeedHandler {

  @Override
  public SeedCoordinate serves() {
    return DoctorCoordinate.READINESS_VERDICT;
  }

  /**
   * Each checkpoint's intrinsic severity — the doctor's vocabulary. systemd-adapter: master can
   * provision without the dbus adapter (degraded), so a failure is a WARNING unless overridden.
   */
  private static final Map<String, Severity> INTRINSIC =
      Map.of("systemd-adapter", Severity.WARNING);

  private static final Severity DEFAULT_INTRINSIC = Severity.WARNING;

  private final SeedCodec codec = new SeedCodec();

  @Override
  public SeedEnvelope handle(SeedEnvelope checkpoint) {
    final ReadinessCheckpoint decoded = codec.decode(checkpoint, ReadinessCheckpoint.class);
    final String scenarioId = decoded.scenarioId();

    final Severity effective =
        decoded.override().flatMap(Severity::parse).orElseGet(() -> intrinsicFor(scenarioId));

    final Action action = effective == Severity.CRITICAL ? Action.STOP : Action.CONTINUE_DEGRADED;
    final ReadinessVerdict verdict =
        new ReadinessVerdict(action, scenarioId + " severity=" + effective.name().toLowerCase());
    return SeedEnvelope.of(DoctorCoordinate.READINESS_VERDICT, codec.encode(verdict));
  }

  private Severity intrinsicFor(String scenarioId) {
    return INTRINSIC.getOrDefault(scenarioId, DEFAULT_INTRINSIC);
  }
}
