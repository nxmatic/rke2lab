package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import java.util.Map;

/** Then stage: asserts on the recorded snapshot. */
class ThenSystemdAdapter extends Stage<ThenSystemdAdapter> {

  @ExpectedScenarioState Map<String, Object> snapshot;

  ThenSystemdAdapter the_dbus_endpoint_responds() {
    assertEquals("ok", snapshot.get("status"));
    return self();
  }

  ThenSystemdAdapter the_probe_reports_status(@Quoted String expectedStatus) {
    assertEquals(expectedStatus, snapshot.get("status"));
    return self();
  }

  ThenSystemdAdapter the_summary_mentions(@Quoted String fragment) {
    final String summary = String.valueOf(snapshot.get("summary"));
    assertTrue(
        summary.contains(fragment), "expected summary to contain \"" + fragment + "\": " + summary);
    return self();
  }
}
