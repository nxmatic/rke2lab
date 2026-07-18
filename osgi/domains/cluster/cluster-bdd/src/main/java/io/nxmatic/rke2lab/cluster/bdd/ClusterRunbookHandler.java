package io.nxmatic.rke2lab.cluster.bdd;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import org.osgi.service.component.annotations.Component;

/**
 * The cluster domain's runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. Extends {@link GenericRunbookHandler} and supplies only the coordinate it serves ({@code
 * cluster}) and the bundle-private scenario the launcher plays. It reads no trigger, so it keeps
 * the default no-op {@code seedFrom}. See docs/architecture/osgi/seed-broker-spec.adoc (§ playing a
 * scion scenario is a sow).
 */
@Component(service = SeedHandler.class)
public final class ClusterRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("cluster");

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return ClusterReadinessScenario.class;
  }
}
