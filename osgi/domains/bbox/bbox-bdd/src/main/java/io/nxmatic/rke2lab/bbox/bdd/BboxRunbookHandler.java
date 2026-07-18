package io.nxmatic.rke2lab.bbox.bdd;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import org.osgi.service.component.annotations.Component;

/**
 * The bbox domain's runbook handler — the OSGi-side grower behind the broker's one host→scion door.
 * Extends {@link GenericRunbookHandler} (the one play-and-graft body shared by every domain) and
 * supplies only the two data answers bbox varies: the coordinate it serves ({@code bbox}) and the
 * bundle-private scenario the launcher plays on THIS bundle's loader. It reads no trigger (bbox's
 * desired state is a static blueprint), so it keeps the default no-op {@code seedFrom}. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ playing a scion scenario is a sow).
 */
@Component(service = SeedHandler.class)
public final class BboxRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("bbox");

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return BboxReconciliationScenario.class;
  }
}
