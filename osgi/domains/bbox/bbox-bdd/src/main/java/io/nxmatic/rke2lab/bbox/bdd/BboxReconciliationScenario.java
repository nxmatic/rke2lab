package io.nxmatic.rke2lab.bbox.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.nxmatic.rke2lab.bbox.contract.BboxAction;
import io.nxmatic.rke2lab.bbox.contract.BboxCoordinate;
import io.nxmatic.rke2lab.bbox.contract.BboxHarvest;
import io.nxmatic.rke2lab.bbox.contract.BboxReconciler;
import io.nxmatic.rke2lab.bbox.contract.BboxReservationRequest;
import io.nxmatic.rke2lab.bbox.contract.BboxRowOutcome;
import io.nxmatic.rke2lab.bbox.core.BlueprintRowEnumerator;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.contract.ObservationWire;
import io.nxmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.nxmatic.rke2lab.doctor.contract.SymptomKind;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ConsultationSource;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;

/**
 * The bbox reservation-reconciliation scenario, a production jGiven scenario told in the BBOX
 * DOMAIN's own vocabulary — the run's desired reservations (enumerated from the netplan blueprint)
 * reconciled through {@link BboxReconciler} against the router, no host/Pulumi type. Played
 * IN-CONTAINER by the engine so the runbook shows a real node of the OSGi world; it lives in {@code
 * bbox-bdd} (only ports + the pure {@link BlueprintRowEnumerator}, no sealed internal), not a
 * {@code -test} fragment (it is live seeding logic).
 *
 * <p>Its collaborators are INJECTED from its OWN bundle's service registry by the {@link
 * OsgiService} bridge: the {@link BboxReconciler} contact, the ambient {@link RunGate} (whose
 * {@link RunGate#cultivating() cultivating} decides live-apply vs dry-run — the SCION consults it,
 * not the edge), and, on a refused row, the doctor's {@link ConsultingService} (an optional
 * snapshot). The scenario is identical live and in test; only who published the collaborators
 * differs (the live {@code LiveBboxReconciler} + the host's RunGate, or the mocks a test seeds into
 * the registry before playing).
 */
@SeedScenario
public class BboxReconciliationScenario
    extends ScenarioTestBase<
        BboxReconciliationScenario.Given,
        BboxReconciliationScenario.When,
        BboxReconciliationScenario.Then>
    implements CellarReceiver<Cellar>, ConsultationSource, ScenarioPlayer.Playable {

  /** The router base-URI the scion reconciles against; the mock edge ignores it. */
  private static final URI ROUTER = URI.create("http://bbox.local");

  /** The bbox admin secret; the mock edge ignores it, a fixed marker suffices in the scenario. */
  private static final String ADMIN_PASSWORD = "admin";

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The transactional cellar the extension injects before the body (store→tag, not
  // registry-resolved).
  // @MonotonicNonNull: null until receiveCellar sets it (before the body), then read, never
  // re-null.
  @MonotonicNonNull private Cellar cellar;

  // The collaborators the OsgiServiceExtension injects from THIS bundle's registry before the body
  // (the @Reference a Jupiter-instantiated scenario cannot have). Uniform Optional (never null —
  // the
  // bridge owns presence): the required ones await SCR and the bridge throws if absent, so their
  // orElseThrow never fires; the doctor is await=false — a snapshot, empty when a world booted
  // without it.
  @OsgiService private Optional<BboxReconciler> contact = Optional.empty();
  @OsgiService private Optional<RunGate> gate = Optional.empty();
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  @OsgiService(await = false)
  private Optional<ConsultingService> doctor = Optional.empty();

  // The consultations the run raised on a refused row — the ScenarioOutcomeExtension PULLS them
  // (ConsultationSource) at the run boundary. Set in the @Test after the body (jGiven defers a
  // failed step's throw to scenario-end, so a refused row still reaches this); empty until then.
  private List<SeedEnvelope> consultations = List.of();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(Cellar cellar) {
    this.cellar = cellar;
  }

  @Override
  public List<SeedEnvelope> consultations() {
    return consultations;
  }

  @Test
  void the_reservations_are_reconciled() {
    final List<BboxReservationRequest> desired = new BlueprintRowEnumerator().rows();
    // The @Test body OWNS the outcome sink (the same discipline as cluster's observations map): the
    // When fills it, and the consult below reads THIS reference — independent of jGiven's stage
    // state after a fail-fast step, so a refused row still reaches the consult.
    final List<BboxRowOutcome> outcomes = new ArrayList<>();
    given()
        .the_desired_reservations(desired.size())
        .and()
        .reconciled_through(contact.orElseThrow(), gate.orElseThrow(), outcomes);
    when().the_run_condition_is_read().and().the_reservations_are_reconciled_against_the_router();
    then()
        .every_row_has_an_outcome()
        .and()
        .no_failed_row_is_silently_dropped()
        .and()
        .the_harvest_is_stored(cellar, parcel.orElseThrow());
    this.consultations = consultOnRefusal(outcomes);
  }

  /**
   * The domain consults the doctor ITSELF on a refused row (the same fork-B pattern as cluster: the
   * scenario owns its consult, not the host). Any {@link BboxAction#FAILED} row is a symptom; if
   * any is present, resolve the doctor's {@link ConsultingService} from THIS bundle's registry,
   * build a {@code readiness-checkpoint} SeedEnvelope carrying a {@code RESERVATION_REFUSED}
   * observation per failed row, and consult. A healthy run reconciled every row, so it consults no
   * one (empty list).
   */
  private List<SeedEnvelope> consultOnRefusal(List<BboxRowOutcome> outcomes) {
    final List<ObservationWire> refused =
        outcomes.stream()
            .filter(o -> o.action() == BboxAction.FAILED)
            .map(BboxReconciliationScenario::refusedObservation)
            .toList();
    if (refused.isEmpty()) {
      return List.of();
    }
    return doctor
        .map(consulting -> List.of(consulting.consult(consultCheckpoint(refused))))
        .orElseGet(List::of);
  }

  /** One {@code RESERVATION_REFUSED} observation for a failed reconciliation row. */
  private static ObservationWire refusedObservation(BboxRowOutcome outcome) {
    return new ObservationWire(
        "failed",
        outcome.cluster() + "/" + outcome.node() + ": reservation refused",
        Optional.of(SymptomKind.RESERVATION_REFUSED),
        Map.of("cluster", outcome.cluster(), "node", outcome.node(), "mac", outcome.mac()));
  }

  /**
   * The {@code readiness-checkpoint} SeedEnvelope the domain hands the doctor — its refused rows.
   */
  private static SeedEnvelope consultCheckpoint(List<ObservationWire> refused) {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            Checkpoint.BBOX_RESERVATIONS.slug(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            refused);
    final SeedCodec codec = new SeedCodec();
    return SeedEnvelope.of(DoctorCoordinate.READINESS_CHECKPOINT, codec.encode(checkpoint));
  }

  /**
   * Given: the desired reservations, the reconciler contact, the run gate, and the outcome sink.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState int desiredCount;
    @ProvidedScenarioState BboxReconciler contact;
    @ProvidedScenarioState RunGate gate;
    @ProvidedScenarioState List<BboxRowOutcome> outcomes;

    public Given the_desired_reservations(@Quoted int count) {
      this.desiredCount = count;
      return self();
    }

    @Hidden
    public Given reconciled_through(
        BboxReconciler contact, RunGate gate, List<BboxRowOutcome> outcomes) {
      this.contact = contact;
      this.gate = gate;
      this.outcomes = outcomes;
      return self();
    }
  }

  /**
   * When: the scion reads the run condition (the {@link RunGate}), derives {@code dryRun =
   * !cultivating()}, and drives the reconciler once. Under a closed gate the edge diffs but does
   * not write (its native DRY_RUN mode → {@code WOULD_*} outcomes), so the scion plays honestly
   * without touching the router. The outcomes are the material the Then asserts and the domain's
   * consult reads.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState BboxReconciler contact;
    @ExpectedScenarioState RunGate gate;
    @ExpectedScenarioState List<BboxRowOutcome> outcomes;
    @ProvidedScenarioState boolean dryRun;

    public When the_run_condition_is_read() {
      this.dryRun = !gate.cultivating();
      return self();
    }

    public When the_reservations_are_reconciled_against_the_router() {
      // Fill the @Test-owned sink (not a fresh field) so the consult reads the outcomes even after
      // the Then's fail-fast on a refused row.
      outcomes.addAll(
          contact.reconcile(ROUTER, ADMIN_PASSWORD, dryRun, new BlueprintRowEnumerator().rows()));
      return self();
    }
  }

  /**
   * Then: every desired row has an outcome, and a failed row is surfaced (never silently dropped) —
   * a FAILED row fails the step so jGiven marks it, but the outcomes are still exposed for the
   * domain's own doctor consult after the play.
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState int desiredCount;
    @ExpectedScenarioState List<BboxRowOutcome> outcomes;
    @ExpectedScenarioState boolean dryRun;

    public Then every_row_has_an_outcome() {
      if (outcomes.size() != desiredCount) {
        throw new AssertionError("expected " + desiredCount + " outcomes, got " + outcomes.size());
      }
      return self();
    }

    public Then no_failed_row_is_silently_dropped() {
      final long failed = outcomes.stream().filter(o -> o.action() == BboxAction.FAILED).count();
      if (failed > 0) {
        throw new AssertionError(failed + " reservation row(s) refused by the router");
      }
      return self();
    }

    /**
     * The scion harvests AND stores — the reversal made concrete (§ host-cellar-realisation,
     * every-scion-contributes). It folds the outcomes into a {@link BboxHarvest} and stores it at
     * the {@code bbox-reservations} coordinate under the current {@link Parcel}; on the Pulumi
     * realisation this store PRODUCES the bbox resource. The scion holds ONE verb ({@code store}) —
     * the cellar consults the RunGate itself to route conserve ({@code up}) vs pre-reserve ({@code
     * preview}), so the scion never picks the mode.
     */
    public Then the_harvest_is_stored(@Hidden Cellar cellar, @Hidden Parcel parcel) {
      final BboxHarvest harvest = BboxHarvest.of(dryRun, outcomes);
      cellar.store(parcel, BboxCoordinate.BBOX_RESERVATIONS, harvest);
      return self();
    }
  }
}
