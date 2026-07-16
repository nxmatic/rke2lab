package io.nxmatic.rke2lab.incus.bdd;

import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import org.osgi.service.component.annotations.Component;

/**
 * The incus RECONCILE runbook handler — the twin of {@link IncusRunbookHandler}, behind a SECOND
 * host→scion door. It serves {@link IncusScenario#RECONCILE}'s runbook coordinate ({@code
 * incus-reconcile}), and its {@link #handle} plays {@link IncusReconcileScenario} in-container
 * through {@link IncusBddScenarios#runReconcile}. Its sibling is {@code IncusRunbookHandler}
 * serving {@link IncusScenario#PROVISION} ({@code incus-provision}) — the two incus scenarios are
 * peer coordinates, neither englobing the other (the single-source soils live on {@link
 * IncusScenario}). The trigger carries no input (reconcile derives its state from the cellar); the
 * ambient transaction {@code cellar} is flattened at the launcher boundary so the scion inherits
 * this run's in-flight staging (the one prepare published, § seed-broker-spec, the entries
 * descend).
 */
@Component(service = SeedHandler.class)
public final class IncusReconcileRunbookHandler implements SeedHandler {

  private static final RunbookCoordinate COORDINATE = IncusScenario.RECONCILE.runbook();

  @Override
  public SeedCoordinate serves() {
    return COORDINATE;
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope trigger) {
    final ScenarioCellar transaction = (ScenarioCellar) cellar;
    try {
      return SeedEnvelope.of(
          COORDINATE,
          IncusBddScenarios.runReconcile(
              transaction.transactionId(), transaction.entriesEncoded()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted playing the incus reconcile scenario in-container", interrupted);
    }
  }
}
