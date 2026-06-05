package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.Map;

/** When stage: runs the injected probe and records the resulting snapshot. */
public class WhenSystemdAdapter extends Stage<WhenSystemdAdapter> {

  @ExpectedScenarioState BootstrapConfig config;
  @ExpectedScenarioState SystemdAdapterProbe probe;

  @ProvidedScenarioState Map<String, Object> snapshot;

  public WhenSystemdAdapter the_systemd_adapter_probe_runs() {
    snapshot = probe.probe(config);
    return self();
  }
}
