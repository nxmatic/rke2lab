package io.nxmatic.rke2lab.seed.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;

/**
 * The conservation bookends of the runbook: {@code fetch} opens (read the parcel's state — where we
 * start from), {@code store} closes (file the harvest). Between them everything is cultivated fresh
 * — no scion reads the cellar. Both ends touch the {@link Cellar}; the four crossings between them
 * only sow and graft.
 *
 * <p>The {@link Cellar} is handed in, not resolved here: seed-bdd is foundation and cannot name a
 * realisation ({@code PulumiCellar} is host, a git-backed cellar is another exec's). The exec
 * injects the one its gardening version uses through the {@link Hidden} wiring step, the way every
 * other stage receives its collaborators. seed-bdd depends only on the neutral {@link Cellar} seam.
 */
public class CellarStage extends Stage<CellarStage> {

  @ScenarioState private Cellar cellar;
  @ScenarioState private Parcel parcel;

  /** The parcel's fetched state — published for a stage that wants to reason on where we start. */
  @ProvidedScenarioState private List<SeedEnvelope> fetched = List.of();

  /** Hand in the conservation collaborators: the realised {@link Cellar} and the {@link Parcel}. */
  @Hidden
  public CellarStage conserving(Cellar cellar, Parcel parcel) {
    this.cellar = cellar;
    this.parcel = parcel;
    return self();
  }

  /**
   * Open: go to the cellar for the parcel's state (the timeline, oldest first). An absent or empty
   * cellar yields nothing — a legitimate first sowing, idempotent.
   */
  public CellarStage the_parcels_state_is_fetched() {
    this.fetched = cellar.fetch(parcel);
    return self();
  }

  /**
   * Close: store one reaped végétal to the parcel's cellar (append-only). The harvest of a crossing
   * — a végétal the run cultivated fresh — filed under its coordinate; the cellar never opens it.
   */
  public CellarStage the_harvest_is_stored(SeedEnvelope vegetal) {
    cellar.store(parcel, vegetal);
    return self();
  }
}
