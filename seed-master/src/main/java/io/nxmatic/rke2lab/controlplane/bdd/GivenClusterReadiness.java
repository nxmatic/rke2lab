package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;

/**
 * Given stage for the cluster-readiness checkpoint: establishes the bootstrap config, the per-phase
 * probe, and the upstream systemd-adapter probe whose scenario is played nested (the dependency
 * edge — cluster-ready depends on systemd-adapter).
 */
public class GivenClusterReadiness extends Stage<GivenClusterReadiness> {

  @ProvidedScenarioState BootstrapConfig config;
  @ProvidedScenarioState ClusterReadinessProbe phaseProbe;
  @ProvidedScenarioState SystemdAdapterProbe systemdAdapterProbe;

  public GivenClusterReadiness the_cluster(@Quoted String name, @Hidden BootstrapConfig config) {
    this.config = config;
    return self();
  }

  @Hidden
  public GivenClusterReadiness with_phase_probe(ClusterReadinessProbe phaseProbe) {
    this.phaseProbe = phaseProbe;
    return self();
  }

  /** The upstream dependency's probe — its scenario is played nested in the When stage. */
  @Hidden
  public GivenClusterReadiness depending_on_systemd_adapter(
      SystemdAdapterProbe systemdAdapterProbe) {
    this.systemdAdapterProbe = systemdAdapterProbe;
    return self();
  }
}
