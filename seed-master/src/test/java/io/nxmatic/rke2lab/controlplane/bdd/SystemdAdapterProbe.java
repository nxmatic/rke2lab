package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.Map;

/**
 * Seam standing in for the readiness probe. The returned envelope mirrors {@code
 * SeedSystemdAdapterEndpointGate.ensureReachable}: a map carrying at least a {@code status} key
 * ({@code "ok"} when reachable) and a human {@code summary}. The fake lets the scenario run offline
 * so the Given/When/Then prose can be validated before wiring to live infrastructure.
 */
@FunctionalInterface
interface SystemdAdapterProbe {
  Map<String, Object> probe(BootstrapConfig config);
}
