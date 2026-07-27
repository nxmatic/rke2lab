package io.nxmatic.rke2lab.worktree.bdd;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import io.nxmatic.rke2lab.worktree.WorktreeRunbookInput;
import java.util.function.Consumer;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.osgi.service.component.annotations.Component;

/**
 * The worktree domain's runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. Extends {@link GenericRunbookHandler} and supplies the coordinate it serves ({@code
 * worktree}, a seam {@link RunbookCoordinate} the flat host can route to across the realm) and the
 * bundle-private {@link WorktreeScenario} the launcher plays — the soil that harvests the worktree
 * facts into the cellar. Like the incus scion it READS the trigger: it decodes the activation input
 * ({@link WorktreeRunbookInput}, carrying the entry-gate {@code FACET} the host amended at the
 * {@code worktree} door) and routes it through the scenario's {@link WorktreeScenario#INPUT}
 * channel, so the run's gate policy drives the crossing's enforcement.
 */
@Component(service = SeedHandler.class)
public final class WorktreeRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("worktree");

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return WorktreeScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return WorktreeScenario.INPUT.into(codec.decode(trigger.payload(), WorktreeRunbookInput.class));
  }
}
