package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;

/**
 * The readiness probe the scenario runs. Production plays the scenario with the real probe ({@code
 * SeedSystemdAdapterEndpointGate::ensureReachable}); tests inject a fake. It returns an {@link
 * Observation}: the captured snapshot, carrying a {@code status} ({@code "ok"} when reachable), a
 * human {@code summary}, and — on a non-ok result — the typed {@link Symptom} the doctor routes on.
 * The observation's {@link Observation#toOutputMap()} view flows downstream into {@code
 * SystemdAdapterResource}.
 */
@FunctionalInterface
public interface SystemdAdapterProbe {
  Observation probe(BootstrapConfig config);
}
