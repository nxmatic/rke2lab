package io.nxmatic.rke2lab.incus.bdd;

import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import org.osgi.service.component.annotations.Component;

/**
 * The incus domain's runbook handler — the OSGi-side grower behind the broker's one host→scion
 * door. It serves {@code RunbookCoordinate("incus")} (a neutral value coordinate the host sows
 * holding only the soil name), and its {@link #handle} plays THIS bundle's scenario in-container
 * through the front-door {@link IncusBddScenarios#run()}, on this bundle's own loader — so the host
 * never names a {@code *BddScenarios} type nor reaches into the incus world. The reaped {@link
 * SeedEnvelope} carries the serialized {@code RunbookEnvelope} (runbook JSON + any consultations),
 * which the host rebuilds and grafts host-side ({@code ScenarioGraft}). See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ playing a scion scenario is a sow).
 */
@Component(service = SeedHandler.class)
public final class IncusRunbookHandler implements SeedHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("incus");

  private final SeedCodec codec = new SeedCodec();

  @Override
  public SeedCoordinate serves() {
    return COORDINATE;
  }

  @Override
  public SeedEnvelope handle(SeedEnvelope trigger) {
    final IncusRunbookInput input = codec.decode(trigger.payload(), IncusRunbookInput.class);
    try {
      return SeedEnvelope.of(COORDINATE, IncusBddScenarios.run(input));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted playing the incus scenario in-container", interrupted);
    }
  }
}
