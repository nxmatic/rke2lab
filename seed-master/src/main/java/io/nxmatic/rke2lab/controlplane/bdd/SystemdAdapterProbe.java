package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;

/**
 * The readiness probe the scenario runs. Production plays the scenario with the real probe ({@code
 * SeedSystemdAdapterEndpointGate::ensureReachable}); tests inject a fake. It returns a {@link
 * Dossier}: the captured snapshot, carrying a {@code status} ({@code "ok"} when reachable), a human
 * {@code summary}, and — on a non-ok result — the typed {@link Symptom} the doctor routes on. The
 * dossier's {@link Dossier#toOutputMap()} view flows downstream into {@code
 * SystemdAdapterResource}.
 */
@FunctionalInterface
public interface SystemdAdapterProbe {
  Dossier probe(BootstrapConfig config);
}
