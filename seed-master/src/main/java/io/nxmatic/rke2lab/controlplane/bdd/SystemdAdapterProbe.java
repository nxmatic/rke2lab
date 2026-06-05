package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.Map;

/**
 * The readiness probe the scenario runs. Production plays the scenario with the real probe ({@code
 * SeedSystemdAdapterEndpointGate::ensureReachable}); tests inject a fake. The returned envelope is
 * the gate's contract: a map carrying at least a {@code status} key ({@code "ok"} when reachable)
 * and a human {@code summary}, which flows downstream into {@code SystemdAdapterResource} outputs.
 */
@FunctionalInterface
public interface SystemdAdapterProbe {
  Map<String, Object> probe(BootstrapConfig config);
}
