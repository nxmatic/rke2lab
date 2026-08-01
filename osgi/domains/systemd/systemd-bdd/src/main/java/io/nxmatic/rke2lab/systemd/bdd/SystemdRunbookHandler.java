package io.nxmatic.rke2lab.systemd.bdd;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import io.nxmatic.rke2lab.systemd.contract.SystemdRunbookInput;
import java.util.function.Consumer;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.osgi.service.component.annotations.Component;

/**
 * The systemd domain's runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. Extends {@link GenericRunbookHandler} and supplies the coordinate it serves ({@code
 * systemd}) and the bundle-private scenario the launcher plays. Like the incus scion it READS the
 * trigger: {@link #seedFrom} decodes the activation input ({@link SystemdRunbookInput}, carrying
 * the {@code @Amendment(FACET)} identity the scenario derives the probe endpoint from) and routes
 * it through the scenario's {@link SystemdAdapterScenario#INPUT} channel. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ playing a scion scenario is a sow).
 */
@Component(service = SeedHandler.class)
public final class SystemdRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("systemd");

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return SystemdAdapterScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return SystemdAdapterScenario.INPUT.into(
        codec.decode(trigger.payload(), SystemdRunbookInput.class));
  }
}
