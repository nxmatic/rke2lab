package io.seedmatic.rke2lab.bbox.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.bbox.contract.BboxAction;
import io.seedmatic.rke2lab.bbox.contract.BboxCoordinate;
import io.seedmatic.rke2lab.bbox.contract.BboxHarvest;
import io.seedmatic.rke2lab.bbox.contract.BboxReconciler;
import io.seedmatic.rke2lab.bbox.contract.BboxReservationRequest;
import io.seedmatic.rke2lab.bbox.contract.BboxRowOutcome;
import io.seedmatic.rke2lab.bbox.contract.BboxRunbookInput;
import io.seedmatic.rke2lab.bbox.core.BlueprintRowEnumerator;
import io.seedmatic.rke2lab.doctor.contract.Checkpoint;
import io.seedmatic.rke2lab.doctor.contract.ConsultingService;
import io.seedmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.seedmatic.rke2lab.doctor.contract.ObservationWire;
import io.seedmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.seedmatic.rke2lab.doctor.contract.SymptomKind;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ConsultationSource;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The bbox reservation-reconciliation scenario, a production jGiven scenario told in the BBOX
 * DOMAIN's own vocabulary — the run's desired reservations (enumerated from the netplan blueprint)
 * reconciled through {@link BboxReconciler} against the router, no host/Pulumi type. Played
 * IN-CONTAINER by the engine so the runbook shows a real node of the OSGi world; it lives in {@code
 * bbox-bdd} (only ports + the pure {@link BlueprintRowEnumerator}, no sealed internal), not a
 * {@code -test} fragment (it is live seeding logic).
 *
 * <p>MODE-BLIND: it injects the {@link BboxReconciler} contact and NO {@code RunGate}. The bridge
 * reads the ambient gate ONCE and resolves the cultivating or surveying impl by LDAP filter on
 * {@code rke2lab.gardening} — the scenario body is identical either way, it just drives the
 * reconciler it was handed. On a refused row it also injects the doctor's {@link ConsultingService}
 * (an optional snapshot). The scenario is identical live and in test; only who published the
 * collaborators differs (the live {@code Cultivating}/{@code SurveyingBboxReconciler} pair the
 * frontier picks between, or the mocks a test seeds into the registry before playing).
 */
@SeedScenario
public class BboxReconciliationScenario
    extends ScenarioTestBase<
        BboxReconciliationScenario.Given,
        BboxReconciliationScenario.When,
        BboxReconciliationScenario.Then>
    implements CellarReceiver<Cellar>,
        ConsultationSource,
        InputReceiver<BboxRunbookInput>,
        ScenarioPlayer.Playable {

  /**
   * The inbound channel the runbook handler ({@code BboxRunbookHandler.seedFrom}) seeds the {@link
   * BboxRunbookInput} router contact through and this scenario receives it from (via {@link
   * InputReceiver}). Single-sourced here — the receiver owns the key + type — and referenced by the
   * handler for the seeding end ({@code INPUT.into(input)}). Registered as a {@link
   * RegisterExtension} so its post-processor fires before the body reads {@link #input}.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<BboxRunbookInput> INPUT =
      new ScenarioInputSeed<>(BboxRunbookInput.class, "bbox-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The router contact the front-door seeds before the body (InputReceiver) — the uri + the live
  // password the host amended from .secrets (empty on a survey/mock, which ignores it). The WHEN
  // reconciles against it. @MonotonicNonNull: null until receiveInput sets it (before the body).
  @MonotonicNonNull private BboxRunbookInput input;

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
  // contact (the reconciler) moved to the When stage (@OsgiService there, filled by the stage
  // creator); parcel stays here — an ambient identity seed threaded to the Then, not a domain edge.
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
  public void receiveInput(BboxRunbookInput input) {
    this.input = input;
  }

  @Override
  public List<SeedEnvelope> consultations() {
    return consultations;
  }

  @Test
  void the_reservations_are_reconciled() {
    final BboxRunbookInput.Router router =
        Objects.requireNonNull(input, "the router contact was not seeded before the body").router();
    final List<BboxReservationRequest> desired = new BlueprintRowEnumerator().rows();
    // The @Test body OWNS the outcome sink (the same discipline as cluster's observations map): the
    // When fills it, and the consult below reads THIS reference — independent of jGiven's stage
    // state after a fail-fast step, so a refused row still reaches the consult.
    final List<BboxRowOutcome> outcomes = new ArrayList<>();
    given()
        .the_desired_reservations(desired.size())
        .and()
        .the_router_contact(router)
        .and()
        .reconciled_through(outcomes);
    when().the_reservations_are_reconciled_against_the_router();
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

  /** Given: the desired reservations, the router contact, and the outcome sink. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState int desiredCount;
    @ProvidedScenarioState BboxRunbookInput.Router router;
    @ProvidedScenarioState List<BboxRowOutcome> outcomes;

    public Given the_desired_reservations(@Quoted int count) {
      this.desiredCount = count;
      return self();
    }

    @Hidden
    public Given the_router_contact(BboxRunbookInput.Router router) {
      this.router = router;
      return self();
    }

    @Hidden
    public Given reconciled_through(List<BboxRowOutcome> outcomes) {
      this.outcomes = outcomes;
      return self();
    }
  }

  /**
   * When: the scion drives the reconciler once, MODE-BLIND. The frontier already chose which impl
   * it holds — cultivating (opens the session, applies) or surveying ({@code WOULD_*} projection,
   * no contact) — so the scion just reconciles; it never branches on the mode. The outcomes are the
   * material the Then asserts and the domain's consult reads.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState BboxRunbookInput.Router router;
    @ExpectedScenarioState List<BboxRowOutcome> outcomes;

    // Injected straight from the bundle registry by the @OsgiService bridge (the stage creator) —
    // not threaded from the scenario through the Given as a step param.
    @OsgiService private Optional<BboxReconciler> contact = Optional.empty();

    public When the_reservations_are_reconciled_against_the_router() {
      // Fill the @Test-owned sink (not a fresh field) so the consult reads the outcomes even after
      // the Then's fail-fast on a refused row. The reconciler is mode-blind: the cultivating impl
      // requires the amended password, the surveying/mock one ignores it (the empty Optional).
      outcomes.addAll(
          contact
              .orElseThrow()
              .reconcile(
                  URI.create(router.uri()),
                  router.password(),
                  new BlueprintRowEnumerator().rows()));
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

    public Then every_row_has_an_outcome() {
      if (outcomes.size() != desiredCount) {
        throw new AssertionError("expected " + desiredCount + " outcomes, got " + outcomes.size());
      }
      return self();
    }

    public Then no_failed_row_is_silently_dropped() {
      final List<BboxRowOutcome> refused =
          outcomes.stream().filter(o -> o.action() == BboxAction.FAILED).toList();
      if (!refused.isEmpty()) {
        // Surface each row's own reason — the step's whole point is that a refusal is never
        // dropped,
        // so its failureMessage must reach the narration, not just a count.
        final String reasons =
            refused.stream()
                .map(
                    o ->
                        o.node()
                            + " ("
                            + o.mac()
                            + "): "
                            + o.failureMessage().orElse("no reason reported"))
                .collect(Collectors.joining("; "));
        throw new BboxReservationError(refused, reasons);
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
      final BboxHarvest harvest = BboxHarvest.of(outcomes);
      cellar.store(parcel, BboxCoordinate.BBOX_RESERVATIONS, harvest);
      return self();
    }
  }
}
