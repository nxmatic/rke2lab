package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;

/** When stage: runs the injected probe and records the resulting dossier. */
public class WhenSystemdAdapter extends Stage<WhenSystemdAdapter> {

  @ExpectedScenarioState BootstrapConfig config;
  @ExpectedScenarioState SystemdAdapterProbe probe;

  @ProvidedScenarioState Dossier dossier;

  public WhenSystemdAdapter the_systemd_adapter_probe_runs() {
    dossier = probe.probe(config);
    return self();
  }
}
