package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;

/**
 * The readiness probe the scenario runs. Live plays the scenario with the real probe ({@code
 * SeedSystemdAdapterEndpointGate::ensureReachable}); tests inject a fake. It returns an {@link
 * ObservationView}: the host-flat captured snapshot, carrying a {@code status} ({@code "ok"} when
 * reachable), a human {@code summary}, and — on a non-ok result — the typed {@link
 * io.nxmatic.rke2lab.seed.broker.port.SymptomKind} the doctor routes on. The view's {@link
 * ObservationView#toOutputMap()} flows downstream into {@code SystemdAdapterResource} and, at the
 * consult boundary, into the checkpoint Document.
 */
@FunctionalInterface
public interface SystemdAdapterProbe {
  ObservationView probe(BootstrapConfig config);
}
