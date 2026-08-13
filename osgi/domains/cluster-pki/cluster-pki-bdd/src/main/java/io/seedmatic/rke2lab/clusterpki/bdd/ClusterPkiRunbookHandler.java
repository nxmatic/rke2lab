package io.seedmatic.rke2lab.clusterpki.bdd;

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
 * The cluster-pki runbook handler — the OSGi-side grower behind the broker's one host→scion door.
 * Extends {@link GenericRunbookHandler} and supplies the coordinate it serves ({@code
 * RunbookCoordinate("cluster-pki")}) and the bundle-private scenario the launcher plays on THIS
 * bundle's loader.
 *
 * <p>Unlike the incus/bbox handlers it is a NO-INPUT scion: {@link ClusterPkiSealScenario} reads
 * the operator's key-store + {@code .sops.yaml} in-container, so there is no trigger to decode —
 * {@link #seedFrom} seeds nothing. The host sows the crossing with an empty {@code {}} trigger (no
 * amendment, no reflector), so {@code Gardening.sow} skips the amend door entirely.
 */
@Component(service = SeedHandler.class)
public final class ClusterPkiRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("cluster-pki");

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return ClusterPkiSealScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    // No input: the scion reads everything in-container. Nothing to seed into the session.
    return store -> {};
  }
}
