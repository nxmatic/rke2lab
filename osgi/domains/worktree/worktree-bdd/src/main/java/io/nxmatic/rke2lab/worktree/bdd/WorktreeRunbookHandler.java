package io.nxmatic.rke2lab.worktree.bdd;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import org.osgi.service.component.annotations.Component;

/**
 * The worktree domain's runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. Extends {@link GenericRunbookHandler} and supplies only the coordinate it serves ({@code
 * worktree}, a seam {@link RunbookCoordinate} the flat host can route to across the realm) and the
 * bundle-private {@link WorktreeScenario} the launcher plays — the soil that harvests the worktree
 * facts into the cellar. It reads no trigger, so it keeps the default no-op {@code seedFrom}.
 */
@Component(service = SeedHandler.class)
public final class WorktreeRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("worktree");

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return WorktreeScenario.class;
  }
}
