package io.nxmatic.rke2lab.manifests.bdd;

import io.nxmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import org.osgi.service.component.annotations.Component;

/**
 * The manifests domain's runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. It serves {@code RunbookCoordinate("manifests")} (a neutral value coordinate the host sows
 * holding only the soil name), and its {@link #handle} plays THIS bundle's scenario in-container
 * through the front-door {@link ManifestsBddScenarios#run}, on this bundle's own loader — so the
 * host never names a {@code *BddScenarios} type nor reaches into the manifests world.
 *
 * <p>THE one difference from {@code BboxRunbookHandler}: it READS the trigger. Bbox ignores its
 * trigger (its desired state is a static blueprint); manifests decodes the activation facet ({@link
 * ManifestsRunbookInput}) off {@code trigger.payload()} and threads it into the run, so the
 * operator's choice — which layers link, which debug — drives the synthesis. The reaped {@link
 * SeedEnvelope} carries the serialized {@code RunbookEnvelope} (runbook JSON, empty consultations).
 * See docs/architecture/osgi/manifests-bdd-spec.adoc (§ the scion trio).
 */
@Component(service = SeedHandler.class)
public final class ManifestsRunbookHandler implements SeedHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("manifests");

  private final SeedCodec codec = new SeedCodec();

  @Override
  public SeedCoordinate serves() {
    return COORDINATE;
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope trigger) {
    final ManifestsRunbookInput facet =
        codec.decode(trigger.payload(), ManifestsRunbookInput.class);
    final ScenarioCellar transaction = (ScenarioCellar) cellar;
    try {
      return SeedEnvelope.of(
          COORDINATE,
          ManifestsBddScenarios.run(
              facet, transaction.transactionId(), transaction.entriesEncoded()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted playing the manifests scenario in-container", interrupted);
    }
  }
}
