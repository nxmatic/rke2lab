package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;

/**
 * Probes one {@link ClusterReadinessPhase}, returning its {@link Observation} (status {@code "ok"}
 * when the phase passes; otherwise a typed {@link Symptom}). Production plays the scenario with a
 * probe backed by the real {@code ClusterBootstrapReadinessVerifier}; tests inject per-phase fakes
 * — the DSL-first path that lets the nested scenario render offline before the live verifier is
 * wired.
 */
@FunctionalInterface
public interface ClusterReadinessProbe {
  Observation probe(BootstrapConfig config, ClusterReadinessPhase phase);
}
