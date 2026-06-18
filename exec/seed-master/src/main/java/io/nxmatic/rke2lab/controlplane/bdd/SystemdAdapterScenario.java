package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;

/**
 * The systemd-adapter readiness scenario, grouped: its {@link Given}/{@link When}/{@link Then}
 * stages live as nested static classes so the scenario reads as one unit. The same stages are
 * played offline (tests) and live (the checkpoint stage), and replayed nested as the cluster
 * checkpoint's dependency edge — see {@link ClusterReadinessScenario}.
 */
public final class SystemdAdapterScenario {

  private SystemdAdapterScenario() {}

  /** Given: establishes the bootstrap config and the probe the scenario will run. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState BootstrapConfig config;
    @ProvidedScenarioState SystemdAdapterProbe probe;

    /**
     * Production passes its real config; tests pass one whose host/port drive the fake's narrative.
     * Only the host shows in the report — the full config dump would drown the prose.
     */
    public Given the_seed_node(@Quoted String host, @Hidden BootstrapConfig config) {
      this.config = config;
      return self();
    }

    /** Hidden from the report: which probe runs is plumbing, the When step is the readable line. */
    @Hidden
    public Given probed_by(SystemdAdapterProbe probe) {
      this.probe = probe;
      return self();
    }
  }

  /** When: runs the injected probe and records the resulting observation. */
  public static class When extends Stage<When> {

    @ExpectedScenarioState BootstrapConfig config;
    @ExpectedScenarioState SystemdAdapterProbe probe;

    @ProvidedScenarioState Observation observation;

    public When the_systemd_adapter_probe_runs() {
      observation = probe.probe(config);
      return self();
    }
  }

  /**
   * Then: asserts on the recorded observation. Plain {@code AssertionError} (not JUnit) keeps this
   * runnable from production when the gate plays the scenario — JGiven marks a throwing step
   * failed.
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState Observation observation;

    public Then the_dbus_endpoint_responds() {
      return the_probe_reports_status("ok");
    }

    public Then the_probe_reports_status(@Quoted String expectedStatus) {
      final String actual = observation.status();
      if (!expectedStatus.equals(actual)) {
        throw new AssertionError(
            "expected status \"" + expectedStatus + "\" but was \"" + actual + "\"");
      }
      return self();
    }

    public Then the_summary_mentions(@Quoted String fragment) {
      final String summary = observation.summary();
      if (summary == null || !summary.contains(fragment)) {
        throw new AssertionError("expected summary to contain \"" + fragment + "\": " + summary);
      }
      return self();
    }

    /**
     * Hidden from the report: lets the gate read the captured observation back as its sink payload.
     */
    @Hidden
    public Observation capturedObservation() {
      return observation;
    }
  }
}
