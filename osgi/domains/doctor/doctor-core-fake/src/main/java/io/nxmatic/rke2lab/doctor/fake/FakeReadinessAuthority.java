package io.nxmatic.rke2lab.doctor.fake;

import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessCheckpoint;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import org.osgi.service.component.annotations.Component;

/**
 * A fake {@link ReadinessAuthority}, published by SCR with {@code variant=fake} so a test
 * connection's service selector resolves it instead of the live {@code DefaultReadinessAuthority} —
 * the two coexist in the registry, told apart only by the property. It honours the operator
 * override when present (so a test can drive a {@code stop} verdict via the checkpoint), else
 * returns {@code continue-degraded}: the intrinsic verdict for systemd-adapter (master provisions
 * without the dbus adapter, degraded). It reasons on the checkpoint only, with no doctor graph.
 */
@Component(
    service = ReadinessAuthority.class,
    property = {"variant=fake", "service.ranking:Integer=-1000"})
public final class FakeReadinessAuthority implements ReadinessAuthority {

  private final DocumentCodec codec = new DocumentCodec();

  @Override
  public Document assess(Document checkpoint) {
    final ReadinessCheckpoint decoded = codec.decode(checkpoint, ReadinessCheckpoint.class);
    final boolean stop =
        decoded
            .override()
            .map(o -> o.equalsIgnoreCase("critical") || o.equalsIgnoreCase("stop"))
            .orElse(false);
    final Action action = stop ? Action.STOP : Action.CONTINUE_DEGRADED;
    final ReadinessVerdict verdict =
        new ReadinessVerdict(action, decoded.scenarioId() + " verdict=" + action.name());
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_VERDICT.slug(), codec.encode(verdict));
  }
}
