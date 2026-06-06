package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.Quoted;

/**
 * Then stage: asserts on the recorded dossier. Plain {@code AssertionError} (not JUnit) keeps this
 * runnable from production when the gate plays the scenario — JGiven marks a throwing step failed.
 */
public class ThenSystemdAdapter extends Stage<ThenSystemdAdapter> {

  @ExpectedScenarioState Dossier dossier;

  public ThenSystemdAdapter the_dbus_endpoint_responds() {
    return the_probe_reports_status("ok");
  }

  public ThenSystemdAdapter the_probe_reports_status(@Quoted String expectedStatus) {
    final String actual = dossier.status();
    if (!expectedStatus.equals(actual)) {
      throw new AssertionError(
          "expected status \"" + expectedStatus + "\" but was \"" + actual + "\"");
    }
    return self();
  }

  public ThenSystemdAdapter the_summary_mentions(@Quoted String fragment) {
    final String summary = dossier.summary();
    if (summary == null || !summary.contains(fragment)) {
      throw new AssertionError("expected summary to contain \"" + fragment + "\": " + summary);
    }
    return self();
  }

  /** Hidden from the report: lets the gate read the captured dossier back as its sink payload. */
  @Hidden
  public Dossier capturedDossier() {
    return dossier;
  }
}
