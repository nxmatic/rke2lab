package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.Quoted;
import java.util.Map;

/**
 * Then stage: asserts on the recorded snapshot. Plain {@code AssertionError} (not JUnit) keeps this
 * runnable from production when the gate plays the scenario — JGiven marks a throwing step failed.
 */
public class ThenSystemdAdapter extends Stage<ThenSystemdAdapter> {

  @ExpectedScenarioState Map<String, Object> snapshot;

  public ThenSystemdAdapter the_dbus_endpoint_responds() {
    return the_probe_reports_status("ok");
  }

  public ThenSystemdAdapter the_probe_reports_status(@Quoted String expectedStatus) {
    final Object actual = snapshot.get("status");
    if (!expectedStatus.equals(actual)) {
      throw new AssertionError(
          "expected status \"" + expectedStatus + "\" but was \"" + actual + "\"");
    }
    return self();
  }

  public ThenSystemdAdapter the_summary_mentions(@Quoted String fragment) {
    final String summary = String.valueOf(snapshot.get("summary"));
    if (!summary.contains(fragment)) {
      throw new AssertionError("expected summary to contain \"" + fragment + "\": " + summary);
    }
    return self();
  }

  /** Hidden from the report: lets the gate read the captured snapshot back as its sink payload. */
  @Hidden
  public Map<String, Object> capturedSnapshot() {
    return snapshot;
  }
}
