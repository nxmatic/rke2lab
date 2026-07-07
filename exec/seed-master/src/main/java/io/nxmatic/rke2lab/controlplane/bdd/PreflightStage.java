package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;

/**
 * Preflight entry gate, as a phase, run through an injected {@link PreflightProbe} (live enforces
 * against the attached framework; tests inject a fake). The framework is derived from the
 * connection WITHOUT owning its lifecycle ({@link BootedFramework#attached}) — the phase is
 * attached to the world, it did not boot it.
 */
public class PreflightStage extends Stage<PreflightStage> {

  @ExpectedScenarioState HostFacts hostFacts;
  @ExpectedScenarioState OsgiConnection connection;
  @ExpectedScenarioState PreflightProbe probe;

  @As("preflight gates are enforced")
  public PreflightStage the_preflight_gates_are_enforced() {
    probe.enforce(hostFacts, BootedFramework.attached(connection.framework()));
    return self();
  }
}
