package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import java.util.Optional;

/**
 * Incus provisioning, as a phase, run through an injected {@link IncusProbe}. The result is an
 * Outcome — {@link Optional}: present when the mutation ran (a live provisioning), empty when
 * deferred. The framework is derived from the connection without owning its lifecycle ({@link
 * BootedFramework#attached}).
 */
public class IncusStage extends Stage<IncusStage> {

  @ExpectedScenarioState HostFacts hostFacts;
  @ExpectedScenarioState OsgiConnection connection;
  @ExpectedScenarioState IncusProbe probe;

  @ProvidedScenarioState Optional<BootstrapResult> bootstrap;

  @As("the incus instance is provisioned")
  public IncusStage the_incus_instance_is_provisioned() {
    this.bootstrap = probe.provision(hostFacts, BootedFramework.attached(connection.framework()));
    return self();
  }
}
