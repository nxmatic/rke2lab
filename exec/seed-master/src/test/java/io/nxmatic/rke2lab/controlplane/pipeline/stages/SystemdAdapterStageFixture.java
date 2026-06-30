package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import io.nxmatic.rke2lab.controlplane.bdd.SystemdAdapterProbe;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority;
import java.util.Map;

/** Builds a SystemdAdapterStage whose probe always fails, for verdict-decision tests. */
final class SystemdAdapterStageFixture {

  private SystemdAdapterStageFixture() {}

  static SystemdAdapterStage failing(ReadinessAuthority authority) {
    final BootstrapConfig config =
        BootstrapConfig.from(io.nxmatic.rke2lab.controlplane.config.Rke2labConfig.defaults());
    final ControlplanePolicy policy = ControlplanePolicy.defaults();
    final SystemdAdapterProbe failingProbe =
        cfg ->
            Observation.failed(
                Symptom.CONNECTION_REFUSED, "fake failure", Map.of("source", "test"));
    // The verdict-decision overload: no runbook / consultation log / doctor — this proof exercises
    // only the failing-probe → authority-verdict path, which reads none of them.
    return new SystemdAdapterStage(
        config,
        policy,
        false, // pulumiMode off → no dry-run, step bodies run
        message -> {},
        failingProbe,
        summary -> {},
        authority);
  }
}
