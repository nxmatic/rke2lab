package io.seedmatic.rke2lab.netplan.bdd;

import io.seedmatic.rke2lab.netplan.contract.NetplanCoordinate;
import io.seedmatic.rke2lab.netplan.contract.NetplanRunbookInput;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.function.Consumer;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.osgi.service.component.annotations.Component;

/**
 * The netplan domain's runbook handler — the OSGi-side grower behind the broker's host→scion door.
 * Extends {@link GenericRunbookHandler} and supplies the coordinate it serves ({@code netplan}) and
 * its scenario. It READS the trigger: {@link #seedFrom} decodes the {@link NetplanRunbookInput}
 * (the SOIL to export into) off {@code trigger.payload()} and routes it through the scenario's
 * {@link NetplanBlueprintScenario#INPUT} channel.
 */
@Component(service = SeedHandler.class)
public final class NetplanRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = NetplanCoordinate.RUNBOOK;

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return NetplanBlueprintScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return NetplanBlueprintScenario.INPUT.into(
        codec.decode(trigger.payload(), NetplanRunbookInput.class));
  }
}
