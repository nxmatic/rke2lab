package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.Map;

/** When stage: runs the injected probe and records the resulting snapshot. */
class WhenSystemdAdapter extends Stage<WhenSystemdAdapter> {

  @ExpectedScenarioState BootstrapConfig config;

  @ProvidedScenarioState SystemdAdapterProbe probe;
  @ProvidedScenarioState Map<String, Object> snapshot;

  WhenSystemdAdapter the_endpoint_is_reachable() {
    probe = FakeSystemdAdapterProbes.reachable();
    return self();
  }

  WhenSystemdAdapter the_endpoint_refuses_the_connection() {
    probe = FakeSystemdAdapterProbes.connectionRefused();
    return self();
  }

  WhenSystemdAdapter the_systemd_adapter_probe_runs() {
    snapshot = probe.probe(config);
    return self();
  }
}
