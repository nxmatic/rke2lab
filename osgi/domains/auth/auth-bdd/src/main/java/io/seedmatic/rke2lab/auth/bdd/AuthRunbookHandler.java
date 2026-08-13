package io.seedmatic.rke2lab.auth.bdd;

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
 * The auth runbook handler — the OSGi-side grower behind the broker's one host→scion door. Extends
 * {@link GenericRunbookHandler} and supplies the coordinate it serves ({@code
 * RunbookCoordinate("auth")}) and the bundle-private scenario the launcher plays on THIS bundle's
 * loader.
 *
 * <p>A NO-INPUT scion, like the cluster-pki seal: {@link AuthSealScenario} reads the token through
 * the injected {@code AuthTokenContact} edge in-container, so there is no trigger to decode —
 * {@link #seedFrom} seeds nothing, and the host sows the crossing with an empty {@code {}} trigger
 * (no amendment, no reflector).
 */
@Component(service = SeedHandler.class)
public final class AuthRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("auth");

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return AuthSealScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    // No input: the scion resolves the token via the edge in-container. Nothing to seed.
    return store -> {};
  }
}
