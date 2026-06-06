package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.junit5.ScenarioTest;
import io.nxmatic.rke2lab.controlplane.config.ConfigLoader;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The systemd-adapter readiness gate, told as behavior. Runs offline against a fake probe so the
 * Given/When/Then prose can be validated; production plays the same scenario with the real probe.
 */
class SystemdAdapterScenarioTest
    extends ScenarioTest<GivenSystemdAdapter, WhenSystemdAdapter, ThenSystemdAdapter> {

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

  // The three mandatory inputs must be present or the config gate reports them missing; dbus
  // host/port keep their defaults (bioskop-master:12434), which the fake's narrative echoes.
  private static BootstrapConfig config() {
    final Map<String, Map<String, Object>> sections =
        Map.of(
            "incus", Map.of("configDir", "/tmp/rke2lab-bdd-incus"),
            "image", Map.of("sharedFolder", "/tmp/rke2lab-bdd-shared"),
            "worktree", Map.of("dir", "/tmp/rke2lab-bdd-worktree"));
    final Rke2labConfig dto =
        Rke2labConfig.from(ConfigLoader.of(section -> Optional.ofNullable(sections.get(section))));
    return BootstrapConfig.from(dto);
  }
}
