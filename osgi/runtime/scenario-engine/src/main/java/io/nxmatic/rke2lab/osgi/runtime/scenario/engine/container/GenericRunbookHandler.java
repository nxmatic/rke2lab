package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import io.nxmatic.rke2lab.seed.broker.port.TransactionalCellar;

/**
 * The one grower behind every host→scion door — the {@link SeedHandler} body the six {@code
 * *RunbookHandler}s were each copying verbatim (cast the cellar to {@link TransactionalCellar},
 * relay its {@code txId} + entries into the scion's fresh session, play the scenario, serialise the
 * {@link ScenarioOutcome} to a {@link RunbookEnvelope}, wrap the {@code InterruptedException}). It
 * is parameterised by a {@link ScenarioStrategy} — the three axes a domain actually varies
 * (coordinate, scenario class, input fork) — held as {@code this}: a domain's {@code @Component}
 * EXTENDS this and implements {@code ScenarioStrategy}, so the naming component (irreducible — the
 * scenario is bundle-private) carries no logic, only the three data answers.
 *
 * <p>Why abstract-plus-self rather than a separate strategy injected by {@code @Reference}: the
 * component that names the bundle-private scenario class must live in the domain bundle regardless;
 * folding the strategy onto it keeps the piece-count at one per domain (the user's decision — no
 * multi-strategy per domain). The generic body lives ONCE here in the engine; the domain artefact
 * shrinks to the three answers.
 */
public abstract class GenericRunbookHandler implements SeedHandler, ScenarioStrategy {

  private final ScenarioPlayer player = new ScenarioPlayer();
  private final SeedCodec codec = new SeedCodec();

  @Override
  public final SeedCoordinate serves() {
    return coordinate();
  }

  @Override
  public final SeedEnvelope handle(Cellar cellar, SeedEnvelope trigger) {
    // The cellar IS the ambient transaction (§ cellar-transactional): cast to the seam
    // TransactionalCellar (a system-exported single copy — safe across the realm boundary, unlike
    // the dual realm-library ScenarioCellar) and FLATTEN it at the launcher boundary — the txId and
    // the in-flight entries relayed into the scion's fresh session as flat strings (the isolation
    // guard-rail), so the scion inherits the parent's transaction.
    final TransactionalCellar transaction = (TransactionalCellar) cellar;
    try {
      final ScenarioOutcome outcome =
          player.play(
              scenarioClass(),
              seedFrom(trigger)
                  .andThen(transaction.transactionId().map(TxIdSeed::into).orElse(store -> {}))
                  .andThen(CellarEntriesSeed.into(transaction.entriesEncoded(coordinate()))));
      final String runbook = new ScenarioJsonWriter(outcome.runbook()).toString();
      return SeedEnvelope.of(
          coordinate(), codec.encode(new RunbookEnvelope(runbook, outcome.consultations())));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted playing the " + coordinate().domain() + " scenario in-container",
          interrupted);
    }
  }
}
