package io.nxmatic.rke2lab.incus.bdd;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import org.osgi.service.component.annotations.Component;

/**
 * The incus RECONCILE runbook handler — the twin of {@link IncusRunbookHandler}, behind a SECOND
 * host→scion door. Extends {@link GenericRunbookHandler} and supplies only {@link
 * IncusScenario#RECONCILE}'s coordinate ({@code incus-reconcile}) and its scenario. Unlike the
 * provision handler it reads no trigger (reconcile derives its whole state from the ambient
 * cellar), so it keeps the default no-op {@code seedFrom}. Its sibling {@link IncusRunbookHandler}
 * serves {@link IncusScenario#PROVISION} — the two incus scenarios are peer coordinates, the
 * single-source soils on {@link IncusScenario}.
 */
@Component(service = SeedHandler.class)
public final class IncusReconcileRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = IncusScenario.RECONCILE.runbook();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return IncusReconcileScenario.class;
  }
}
