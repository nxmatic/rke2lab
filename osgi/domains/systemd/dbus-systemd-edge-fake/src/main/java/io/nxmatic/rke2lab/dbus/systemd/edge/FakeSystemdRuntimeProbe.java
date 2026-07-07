package io.nxmatic.rke2lab.dbus.systemd.edge;

import io.nxmatic.rke2lab.systemd.port.SystemdProbeRequest;
import io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.systemd.port.SystemdStatusSnapshot;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * A fake {@link SystemdRuntimeProbe}, published by SCR with {@code variant=fake} so a test
 * connection's service selector resolves it instead of the live {@link DbusSystemdProbe} — the two
 * coexist in the registry (both activated when the edge starts and this fragment is attached), told
 * apart only by the property. It contacts no dbus endpoint: it returns a HEALTHY snapshot (the
 * mandatory target reached, the runtime precheck ready, no failed units) so the readiness endpoint
 * gate sees the endpoint as reachable and the happy path plays green, offline.
 */
@Component(
    service = SystemdRuntimeProbe.class,
    property = {"variant=fake", "service.ranking:Integer=-1000"})
public final class FakeSystemdRuntimeProbe implements SystemdRuntimeProbe {

  @Override
  public SystemdStatusSnapshot probe(SystemdProbeRequest request) {
    return SystemdStatusSnapshot.builder()
        .mandatoryTargetState("active")
        .mandatoryTargetHealthy(true)
        .runtimePrecheckReady(true)
        .connectionContext(
            Map.of(
                "dbusHost", request.dbusHost(),
                "dbusPort", Integer.toString(request.dbusPort()),
                "nodeName", request.nodeName(),
                "probeMode", "fake"))
        .summary("fake systemd runtime: mandatory target active, precheck ready")
        .build();
  }
}
