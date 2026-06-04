package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.junit5.ScenarioTest;
import org.junit.jupiter.api.Test;

/**
 * The systemd-adapter readiness gate, told as behavior. Runs offline against a fake probe so the
 * Given/When/Then prose can be validated before wiring to live infrastructure.
 */
class SystemdAdapterScenarioTest
    extends ScenarioTest<GivenSystemdAdapter, WhenSystemdAdapter, ThenSystemdAdapter> {

  @Test
  void systemd_adapter_becomes_reachable() {
    given().an_incus_instance_named("master");
    when().the_endpoint_is_reachable().and().the_systemd_adapter_probe_runs();
    then().the_dbus_endpoint_responds();
  }

  @Test
  void systemd_adapter_endpoint_refused_is_reported() {
    given().an_incus_instance_named("master");
    when().the_endpoint_refuses_the_connection().and().the_systemd_adapter_probe_runs();
    then()
        .the_probe_reports_status("failed")
        .and()
        .the_summary_mentions("Connection refused at bioskop-master:12434");
  }
}
