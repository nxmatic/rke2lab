package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The single carrier of host context into the stages. {@link HostSeeder} fills it from the JUnit
 * session store and pushes it into the run's value-DAG with one call ({@code
 * ScenarioExecutor.readScenarioState(this)}), so the stages resolve their
 * {@code @ExpectedScenarioState} from the same map jGiven uses for phase-to-phase flow. This
 * unifies context injection: there is ONE way a stage receives anything — the DAG — with no
 * parallel {@code *Aware}/{@code accept*} channel on the scenario and no pass-through fields.
 *
 * <p>The fields mirror the {@code @ExpectedScenarioState} the stages read. Overrides that are
 * optional by nature ({@code injectedProbe}, {@code clusterProbe}) are {@link Optional}, defaulting
 * to {@link Optional#empty()} — never null; the live boot leaves them empty and the stage resolves
 * the live probe from the registry. Required context (facts, connection, probes) is set by the
 * seeder from the store; the {@code Resolution.NAME} fields carry an erased type ({@code Optional},
 * {@code AtomicReference}) that would otherwise collide by type in the DAG.
 */
final class StageContext {

  @ProvidedScenarioState HostFacts hostFacts;
  @ProvidedScenarioState OsgiConnection connection;
  @ProvidedScenarioState PreflightProbe preflightProbe;
  @ProvidedScenarioState BboxProbe bboxProbe;
  @ProvidedScenarioState IncusProbe incusProbe;

  @ProvidedScenarioState(resolution = Resolution.NAME)
  Optional<SystemdAdapterProbe> injectedProbe = Optional.empty();

  @ProvidedScenarioState(resolution = Resolution.NAME)
  Optional<ClusterReadinessProbe> clusterProbe = Optional.empty();

  /** The driver's outputs sink — the terminal {@link OutputsStage} publishes the collected map. */
  @ProvidedScenarioState(resolution = Resolution.NAME)
  Optional<AtomicReference<Map<String, Object>>> outputsSink = Optional.empty();
}
