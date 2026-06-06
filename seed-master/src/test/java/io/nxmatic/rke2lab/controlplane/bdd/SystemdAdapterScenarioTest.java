package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.junit5.ScenarioTest;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import org.junit.jupiter.api.Test;

/**
 * The systemd-adapter readiness gate, told as behavior. Runs offline against a fake probe so the
 * Given/When/Then prose can be validated; production plays the same scenario with the real probe.
 */
class SystemdAdapterScenarioTest
    extends ScenarioTest<
        SystemdAdapterScenario.Given, SystemdAdapterScenario.When, SystemdAdapterScenario.Then> {

  @Test
  void systemd_adapter_becomes_reachable() {
    given()
        .the_seed_node("bioskop-master", config())
        .probed_by(FakeSystemdAdapterProbes.reachable());
    when().the_systemd_adapter_probe_runs();
    then().the_dbus_endpoint_responds();
  }

  @Test
  void systemd_adapter_endpoint_refused_is_reported() {
    given()
        .the_seed_node("bioskop-master", config())
        .probed_by(FakeSystemdAdapterProbes.connectionRefused());
    when().the_systemd_adapter_probe_runs();
    then()
        .the_probe_reports_status("failed")
        .and()
        .the_summary_mentions("Connection refused at bioskop-master:12434");
  }

  // Mandatory inputs only; dbus host/port keep their defaults (bioskop-master:12434), which the
  // fake's narrative echoes.
  private static BootstrapConfig config() {
    return OperatorConfiguration.mandatory().asBootstrapConfig();
  }
}
