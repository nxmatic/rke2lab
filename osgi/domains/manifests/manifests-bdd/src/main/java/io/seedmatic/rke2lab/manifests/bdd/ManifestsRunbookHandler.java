package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.manifests.contract.ManifestsCoordinate;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
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
 * The manifests domain's runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. Extends {@link GenericRunbookHandler} and supplies the coordinate it serves ({@code
 * manifests}) and its scenario. Like the incus provision scion it READS the trigger: it overrides
 * {@link #seedFrom} to decode the activation facet ({@link ManifestsRunbookInput} — which layers
 * link, which debug) off {@code trigger.payload()} and route it through the scenario's {@link
 * ManifestSynthesisScenario#INPUT} channel, so the operator's choice drives the synthesis. See
 * docs/architecture/osgi/manifests-bdd-spec.adoc (§ the scion trio).
 */
@Component(service = SeedHandler.class)
public final class ManifestsRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = ManifestsCoordinate.RUNBOOK;

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return ManifestSynthesisScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return ManifestSynthesisScenario.INPUT.into(
        codec.decode(trigger.payload(), ManifestsRunbookInput.class));
  }
}
