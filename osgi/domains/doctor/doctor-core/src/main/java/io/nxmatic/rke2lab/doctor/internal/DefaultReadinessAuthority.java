package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.Severity;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessCheckpoint;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import io.nxmatic.rke2lab.world.gateway.port.SeedHandler;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * The OSGi-side readiness authority: it owns the severity vocabulary the host no longer holds.
 * Given a checkpoint Document (scenario id, failed, optional operator override), it resolves the
 * effective severity — the operator override if present, else the scenario's intrinsic severity —
 * and maps it to a provisioning verdict ({@code stop} iff CRITICAL, else {@code
 * continue-degraded}). Published as a {@link SeedHandler} serving {@code readiness-verdict}, so the
 * broker routes a readiness checkpoint here when the host sows for a verdict; the flat host reads
 * only the verdict's action field.
 *
 * <p>The Document payload is a serialized JSON {@code String}; this authority parses and serializes
 * it with its OWN jackson (a doctor-core bundle dependency) — no jackson type crosses the seam.
 */
@Component(service = SeedHandler.class)
public final class DefaultReadinessAuthority implements SeedHandler {

  @Override
  public Coordinate serves() {
    return Coordinate.READINESS_VERDICT;
  }

  /**
   * Each checkpoint's intrinsic severity — the doctor's vocabulary. systemd-adapter: master can
   * provision without the dbus adapter (degraded), so a failure is a WARNING unless overridden.
   */
  private static final Map<String, Severity> INTRINSIC =
      Map.of("systemd-adapter", Severity.WARNING);

  private static final Severity DEFAULT_INTRINSIC = Severity.WARNING;

  private final DocumentCodec codec = new DocumentCodec();

  @Override
  public Document handle(Document checkpoint) {
    final ReadinessCheckpoint decoded = codec.decode(checkpoint, ReadinessCheckpoint.class);
    final String scenarioId = decoded.scenarioId();

    final Severity effective =
        decoded.override().flatMap(Severity::parse).orElseGet(() -> intrinsicFor(scenarioId));

    final Action action = effective == Severity.CRITICAL ? Action.STOP : Action.CONTINUE_DEGRADED;
    final ReadinessVerdict verdict =
        new ReadinessVerdict(action, scenarioId + " severity=" + effective.name().toLowerCase());
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_VERDICT.slug(), codec.encode(verdict));
  }

  private Severity intrinsicFor(String scenarioId) {
    return INTRINSIC.getOrDefault(scenarioId, DEFAULT_INTRINSIC);
  }
}
