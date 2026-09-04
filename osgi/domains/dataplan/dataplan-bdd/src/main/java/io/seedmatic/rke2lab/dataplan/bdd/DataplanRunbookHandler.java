package io.seedmatic.rke2lab.dataplan.bdd;

import io.seedmatic.rke2lab.dataplan.contract.DataplanCoordinate;
import io.seedmatic.rke2lab.dataplan.contract.DataplanRunbookInput;
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
 * The dataplan domain's runbook handler — the OSGi-side grower behind the broker's host→scion door.
 * Extends {@link GenericRunbookHandler} and supplies the coordinate it serves ({@code dataplan})
 * and its scenario. It READS the trigger: {@link #seedFrom} decodes the {@link
 * DataplanRunbookInput} (the SOIL to export into) off {@code trigger.payload()} and routes it
 * through the scenario's {@link DataplanScenario#INPUT} channel.
 */
@Component(service = SeedHandler.class)
public final class DataplanRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = DataplanCoordinate.RUNBOOK;

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return DataplanScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return DataplanScenario.INPUT.into(codec.decode(trigger.payload(), DataplanRunbookInput.class));
  }
}
