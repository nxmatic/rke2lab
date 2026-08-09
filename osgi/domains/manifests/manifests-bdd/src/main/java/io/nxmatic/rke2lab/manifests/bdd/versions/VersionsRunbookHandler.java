package io.nxmatic.rke2lab.manifests.bdd.versions;

import io.nxmatic.rke2lab.manifests.contract.ManifestVersionsBumpInput;
import io.nxmatic.rke2lab.manifests.contract.ManifestsCoordinate;
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
 * The manifests domain's version-bump runbook handler — the OSGi-side grower behind the broker's
 * host→scion door for the {@link ManifestsCoordinate#VERSIONS} coordinate. Its own plant, distinct
 * from {@code ManifestsRunbookHandler} (the synthesis): it grows {@link VersionBumpScenario},
 * decoding the operator's bump facet ({@link ManifestVersionsBumpInput}) off the reconciled trigger
 * and routing it through the scenario's {@link VersionBumpScenario#INPUT} channel.
 */
@Component(service = SeedHandler.class)
public final class VersionsRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = ManifestsCoordinate.VERSIONS;

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return VersionBumpScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return VersionBumpScenario.INPUT.into(
        codec.decode(trigger.payload(), ManifestVersionsBumpInput.class));
  }
}
