package io.nxmatic.rke2lab.bbox.bdd;

import io.nxmatic.rke2lab.bbox.contract.BboxCoordinate;
import io.nxmatic.rke2lab.bbox.contract.BboxRunbookInput;
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
 * The bbox domain's runbook handler — the OSGi-side grower behind the broker's one host→scion door.
 * Extends {@link GenericRunbookHandler} (the one play-and-graft body shared by every domain) and
 * supplies the coordinate it serves ({@link BboxCoordinate#RUNBOOK}) and the bundle-private
 * scenario the launcher plays on THIS bundle's loader. Like the manifests/incus scions it READS the
 * trigger: it overrides {@link #seedFrom} to decode the router contact ({@link BboxRunbookInput} —
 * the {@code uri} + {@code password} the host amended from {@code .secrets}) off {@code
 * trigger.payload()} and route it through the scenario's {@link BboxReconciliationScenario#INPUT}
 * channel, so the live secret drives the reconcile. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ playing a scion scenario is a sow).
 */
@Component(service = SeedHandler.class)
public final class BboxRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = BboxCoordinate.RUNBOOK;

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return BboxReconciliationScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return BboxReconciliationScenario.INPUT.into(
        codec.decode(trigger.payload(), BboxRunbookInput.class));
  }
}
