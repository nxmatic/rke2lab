package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.function.Consumer;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.osgi.service.component.annotations.Component;

/**
 * The replicator-secrets runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. Supplies the coordinate it serves ({@code RunbookCoordinate("replicator-secrets")}) and the
 * bundle-private {@link ReplicatorSecretsSealScenario}.
 *
 * <p>A NO-INPUT seal scion, like ghapp / cluster-pki / auth: the scenario reads {@code .secrets}
 * through the host {@link io.seedmatic.rke2lab.seed.broker.port.SecretsGateway} seam in-container,
 * so there is no trigger to decode — {@link #seedFrom} seeds nothing and the grow sows the crossing
 * with an empty {@code {}} trigger.
 */
@Component(service = SeedHandler.class)
public final class ReplicatorSecretsRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("replicator-secrets");

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return ReplicatorSecretsSealScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return store -> {};
  }
}
