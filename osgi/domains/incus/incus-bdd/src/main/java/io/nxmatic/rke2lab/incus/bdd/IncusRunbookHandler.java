package io.nxmatic.rke2lab.incus.bdd;

import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.function.Consumer;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.osgi.service.component.annotations.Component;

/**
 * The incus PROVISION runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. Extends {@link GenericRunbookHandler} and supplies {@link IncusScenario#PROVISION}'s
 * coordinate ({@code incus-provision}) and its scenario. Unlike the no-input scions it READS the
 * trigger: it overrides {@link #seedFrom} to decode the activation input ({@link
 * IncusRunbookInput}, carrying the {@code @Amendment(SOIL)} the scenario forwards to the manifests
 * scion it consults) off {@code trigger.payload()} and route it through the scenario's {@link
 * IncusProvisionScenario#INPUT} channel, so the operator's choice drives the provision.
 */
@Component(service = SeedHandler.class)
public final class IncusRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = IncusScenario.PROVISION.runbook();

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return IncusProvisionScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return IncusProvisionScenario.INPUT.into(
        codec.decode(trigger.payload(), IncusRunbookInput.class));
  }
}
