package io.nxmatic.rke2lab.seed.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.seed.broker.port.SeedBroker;

/**
 * The runbook's first gardening step: open the framework and reach the broker. The JUnit launcher
 * plays the root scenario on the HOST classpath, OUTSIDE Felix, so launching the world is a
 * narrated step of the scenario — not a pre-amble. It opens an {@link OsgiConnection} (embedded:
 * boots Felix from the staged bundles the exec-jar carries, and owns the lifecycle) and resolves
 * the ONE {@link SeedBroker} door from the live registry, publishing both into the run's value-DAG
 * for the sow-and-graft callers downstream.
 *
 * <p>The connection is {@link ProvidedScenarioState} so a later teardown step (or the exec) can
 * close it — {@code close()} stops the world it owns. The broker is what every crossing sows
 * through; resolving it here, once, is the gardening equivalent of "the tools are laid out before
 * we sow".
 */
public class OpenFrameworkStage extends Stage<OpenFrameworkStage> {

  /** SCR publishes the broker only after its handlers bind; wait a bounded while for it. */
  private static final long BROKER_TIMEOUT_MILLIS = 30_000;

  @ProvidedScenarioState OsgiConnection connection;
  @ProvidedScenarioState SeedBroker broker;

  public OpenFrameworkStage the_framework_is_launched() {
    this.connection = OsgiConnection.embedded();
    this.broker = connection.awaitService(SeedBroker.class, BROKER_TIMEOUT_MILLIS);
    if (broker == null) {
      throw new IllegalStateException(
          "no SeedBroker published within "
              + BROKER_TIMEOUT_MILLIS
              + "ms — the broker runtime "
              + "bundle (seed-broker-runtime) is not staged, or its handlers never bound");
    }
    return self();
  }
}
