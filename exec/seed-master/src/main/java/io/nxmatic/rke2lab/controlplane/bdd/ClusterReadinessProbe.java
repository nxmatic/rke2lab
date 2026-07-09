package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;

/**
 * Probes one {@link ClusterReadinessPhase}, returning its {@link ObservationView} (status {@code
 * "ok"} when the phase passes; otherwise a typed {@link
 * io.nxmatic.rke2lab.seed.broker.port.SymptomKind}). Live plays the scenario with a probe backed by
 * the real {@code ClusterBootstrapReadinessVerifier}; tests inject per-phase fakes — the DSL-first
 * path that lets the nested scenario render offline before the live verifier is wired.
 */
@FunctionalInterface
public interface ClusterReadinessProbe {
  ObservationView probe(BootstrapConfig config, ClusterReadinessPhase phase);
}
