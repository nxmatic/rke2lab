package io.nxmatic.rke2lab.incus.bdd;

import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import io.nxmatic.rke2lab.seed.broker.port.TransactionalCellar;
import org.osgi.service.component.annotations.Component;

/**
 * The incus domain's runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. It serves {@code RunbookCoordinate("incus-provision")} (a neutral value coordinate the host
 * sows holding only the soil name), and its {@link #handle} plays THIS bundle's scenario
 * in-container through the front-door {@link IncusBddScenarios#run()}, on this bundle's own loader
 * — so the host never names a {@code *BddScenarios} type nor reaches into the incus world. The
 * reaped {@link SeedEnvelope} carries the serialized {@code RunbookEnvelope} (runbook JSON + any
 * consultations), which the host rebuilds and grafts host-side ({@code ScenarioGraft}). See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ playing a scion scenario is a sow).
 */
@Component(service = SeedHandler.class)
public final class IncusRunbookHandler implements SeedHandler {

  private static final RunbookCoordinate COORDINATE = IncusScenario.PROVISION.runbook();

  private final SeedCodec codec = new SeedCodec();

  @Override
  public SeedCoordinate serves() {
    return COORDINATE;
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope trigger) {
    final IncusRunbookInput input = codec.decode(trigger.payload(), IncusRunbookInput.class);
    // The cellar IS the ambient transaction (§ cellar-transactional): cast to the seam
    // TransactionalCellar (a system-exported single copy — safe across the realm boundary, unlike
    // the dual realm-library ScenarioCellar) and FLATTEN it at the launcher boundary — the txId and
    // the in-flight entries relayed into the sub-scion's fresh session, both as flat strings (the
    // isolation guard-rail), so the scion inherits the parent's transaction.
    final TransactionalCellar transaction = (TransactionalCellar) cellar;
    try {
      return SeedEnvelope.of(
          COORDINATE,
          IncusBddScenarios.run(input, transaction.transactionId(), transaction.entriesEncoded()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted playing the incus scenario in-container", interrupted);
    }
  }
}
