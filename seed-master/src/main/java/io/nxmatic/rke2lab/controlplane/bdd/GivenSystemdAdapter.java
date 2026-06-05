package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;

/** Given stage: establishes the bootstrap config and the probe the scenario will run. */
public class GivenSystemdAdapter extends Stage<GivenSystemdAdapter> {

  @ProvidedScenarioState BootstrapConfig config;
  @ProvidedScenarioState SystemdAdapterProbe probe;

  /**
   * Production passes its real config; tests pass one whose host/port drive the fake's narrative.
   * Only the host shows in the report — the full config dump would drown the prose.
   */
  public GivenSystemdAdapter the_seed_node(@Quoted String host, @Hidden BootstrapConfig config) {
    this.config = config;
    return self();
  }

  /** Hidden from the report: which probe runs is plumbing, the When step is the readable line. */
  @Hidden
  public GivenSystemdAdapter probed_by(SystemdAdapterProbe probe) {
    this.probe = probe;
    return self();
  }
}
