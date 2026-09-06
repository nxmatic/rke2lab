package io.seedmatic.rke2lab.cluster.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.cluster.contract.ClusterReadinessContact;
import io.seedmatic.rke2lab.cluster.contract.ClusterReadinessPhase;
import io.seedmatic.rke2lab.cluster.contract.ClusterReadinessSnapshot;
import io.seedmatic.rke2lab.cluster.contract.ControllerRef;
import io.seedmatic.rke2lab.doctor.contract.Checkpoint;
import io.seedmatic.rke2lab.doctor.contract.ConsultingService;
import io.seedmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.seedmatic.rke2lab.doctor.contract.ObservationWire;
import io.seedmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.seedmatic.rke2lab.doctor.contract.SymptomKind;
import io.seedmatic.rke2lab.osgi.runtime.readiness.ReadinessBudget;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ConsultationSource;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ReadinessBudgetReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ReadinessDeadlines;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SurveyInert;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The cluster-readiness checkpoint, a production jGiven scenario told in the CLUSTER DOMAIN's own
 * vocabulary — {@link ClusterReadinessContact} probed across its {@link ClusterReadinessPhase}s
 * over a kubeconfig, no host/Pulumi type. Played IN-CONTAINER by the engine so the runbook shows a
 * real node of the OSGi world; it lives in {@code cluster-bdd} (only ports, no sealed internal),
 * not a {@code -test} fragment (it is live seeding logic).
 *
 * <p>Its collaborator — the {@link ClusterReadinessContact} — is INJECTED from its OWN bundle's
 * service registry by the {@link OsgiService} bridge; the scenario is identical live and in test,
 * only who published the contact differs (the live {@code Fabric8ClusterContact}, or a mock a test
 * seeds into the registry before playing). The phases form a strict chain (kubeconfig → API →
 * controllers): a not-ready phase throws, jGiven marks it FAILED and skips the downstream chained
 * steps, so the runbook shows exactly where readiness broke.
 */
@SeedScenario
public class ClusterReadinessScenario
    extends ScenarioTestBase<
        ClusterReadinessScenario.Given,
        ClusterReadinessScenario.When,
        ClusterReadinessScenario.Then>
    implements ConsultationSource,
        ScenarioPlayer.Playable,
        SurveyInert,
        ReadinessBudgetReceiver,
        InputReceiver<ReadinessInput> {

  /**
   * The inbound channel {@code ClusterRunbookHandler.seedFrom} seeds the reconciled {@link
   * ReadinessInput} through and this scenario receives (via {@link InputReceiver}) — single-sourced
   * here, referenced by the handler for the seeding end ({@code INPUT.into(input)}). Registered as
   * a {@link RegisterExtension} so its post-processor fires before the body reads {@link #input}.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<ReadinessInput> INPUT =
      new ScenarioInputSeed<>(ReadinessInput.class, "cluster-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The activation input the front-door seeds before the body (InputReceiver) — it carries WHERE
  // the operator kubeconfig is published (the FACET the host contributes). @MonotonicNonNull: null
  // until receiveInput sets it (before the body) — and legitimately null in an offline play (no
  // input seeded), where the marker path suffices because the mock contact ignores it.
  @MonotonicNonNull private ReadinessInput input;

  // The two-tier readiness budget the ReadinessBudgetExtension resolves from the @Test's
  // @ReadinessDeadlines (folded with the stack override) before the body — threaded into the
  // contact
  // so awaitReady bounds the API reach + controller convergence. Null until receiveBudget sets it.
  @MonotonicNonNull private ReadinessBudget budget;

  // Injected by the OsgiServiceExtension from THIS bundle's registry before the body. The contact
  // moved to the When stage (@OsgiService there, filled by the stage creator); the doctor stays
  // here — await=false, a snapshot the consult reads, empty when a world booted without it.
  @OsgiService(await = false)
  private Optional<ConsultingService> doctor = Optional.empty();

  // The consultations the run raised on a failing phase — the ScenarioOutcomeExtension PULLS them
  // (ConsultationSource) at the run boundary. Set in the @Test after the body (jGiven defers a
  // failing phase's throw to scenario-end, so it still reaches this); empty until then.
  private List<SeedEnvelope> consultations = List.of();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public List<SeedEnvelope> consultations() {
    return consultations;
  }

  @Override
  public void receiveInput(ReadinessInput input) {
    this.input = input;
  }

  @Override
  public String readinessCheckpoint() {
    return Checkpoint.CLUSTER_READINESS.slug();
  }

  @Override
  public void receiveBudget(ReadinessBudget budget) {
    this.budget = budget;
  }

  @Test
  @ReadinessDeadlines(connect = "PT2M", ready = "PT1M")
  void the_cluster_becomes_ready() {
    final Map<ClusterReadinessPhase, ObservationWire> observations =
        new EnumMap<>(ClusterReadinessPhase.class);
    final ReadinessBudget resolvedBudget =
        Objects.requireNonNull(
            budget, "the ReadinessBudgetExtension must inject the budget before the body");
    given().the_cluster("seed", kubeconfig()).and().probed_through(observations, resolvedBudget);
    when()
        .the_kubeconfig_is_published()
        .and()
        .the_api_is_ready()
        .and()
        .the_required_controllers_are_effective();
    then().the_cluster_is_ready();
    this.consultations = consultOnFailure(observations);
  }

  /**
   * The domain consults the doctor ITSELF on a failing phase (fork B: the checkpoint owns its
   * consult, not the host). A phase reported not-ready recorded its {@link ObservationWire}
   * carrying a typed {@link SymptomKind}; if any is non-ok, resolve the doctor's {@link
   * ConsultingService} from THIS bundle's registry — the same way the contact is resolved — build
   * the {@code readiness-checkpoint} SeedEnvelope around the observations, and consult. The
   * returned {@code consultation} {@code SeedEnvelope}s ride the envelope back to the host, which
   * records them into its shared log (it no longer computes the diagnosis, only renders it). A
   * healthy run raised no symptom, so it consults no one and returns an empty list.
   */
  private List<SeedEnvelope> consultOnFailure(
      Map<ClusterReadinessPhase, ObservationWire> observations) {
    final boolean anySymptom =
        observations.values().stream().anyMatch(o -> o.symptom().isPresent());
    if (!anySymptom) {
      return List.of();
    }
    return doctor
        .map(consulting -> List.of(consulting.consult(consultCheckpoint(observations))))
        .orElseGet(List::of);
  }

  /**
   * The {@code readiness-checkpoint} SeedEnvelope the domain hands the doctor — its observations.
   */
  private static SeedEnvelope consultCheckpoint(
      Map<ClusterReadinessPhase, ObservationWire> observations) {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            Checkpoint.CLUSTER_READINESS.slug(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.copyOf(observations.values()));
    final SeedCodec codec = new SeedCodec();
    return SeedEnvelope.of(DoctorCoordinate.READINESS_CHECKPOINT, codec.encode(checkpoint));
  }

  /**
   * The kubeconfig the checkpoint reads the cluster through: the host's {@link ReadinessInput}
   * FACET path in a live run; an offline placeholder when unamended — the mock contact ignores it,
   * so it only satisfies the kubeconfig-published phase (never the dead {@code /srv/host} path).
   */
  private Path kubeconfig() {
    return Optional.ofNullable(input)
        .flatMap(ReadinessInput::access)
        .map(ReadinessInput.Access::kubeconfigPath)
        .map(Path::of)
        .orElse(UNAMENDED_MARKER);
  }

  private static final Path UNAMENDED_MARKER = Path.of("kubeconfig-unamended");

  /** Given: the kubeconfig to read the cluster through, the controllers to wait on, the contact. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Path kubeconfig;
    @ProvidedScenarioState List<ControllerRef> controllers = List.of();

    /**
     * The per-phase observations the When records — the material the domain's own consult reads.
     */
    @ProvidedScenarioState Map<ClusterReadinessPhase, ObservationWire> observations;

    /** The two-tier readiness budget the When hands the contact's awaitReady. */
    @ProvidedScenarioState ReadinessBudget budget;

    public Given the_cluster(@Quoted String name, @Hidden Path kubeconfig) {
      this.kubeconfig = kubeconfig;
      return self();
    }

    @Hidden
    public Given probed_through(
        Map<ClusterReadinessPhase, ObservationWire> observations, ReadinessBudget budget) {
      this.observations = observations;
      this.budget = budget;
      return self();
    }
  }

  /**
   * When: each readiness phase is its own step, chained in canonical order. Each phase records its
   * {@link ObservationWire} (ok, or failed with a typed {@link SymptomKind}) into the shared map,
   * then a not-ready phase throws — jGiven marks its step FAILED and skips the downstream chained
   * steps. Fail-fast is the chain's own semantics; the recorded observations are what the domain's
   * own doctor consult reads after the play.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState Path kubeconfig;
    @ExpectedScenarioState List<ControllerRef> controllers;
    @ExpectedScenarioState Map<ClusterReadinessPhase, ObservationWire> observations;
    @ExpectedScenarioState ReadinessBudget budget;

    // The snapshot the single awaitReady contact produced — captured by the api-ready step (the
    // reach + convergence run inside it), then READ by the controllers step. The twin of the
    // systemd
    // snapshot: one contact, the phases read its facts rather than each making a contact.
    @ProvidedScenarioState ClusterReadinessSnapshot snapshot;

    // Injected straight from the bundle registry by the @OsgiService bridge (the stage creator) —
    // not threaded from the scenario through the Given as a step param.
    @OsgiService private Optional<ClusterReadinessContact> contact = Optional.empty();

    public When the_kubeconfig_is_published() {
      return check(
          ClusterReadinessPhase.KUBECONFIG_PUBLISHED,
          kubeconfig != null,
          SymptomKind.KUBECONFIG_MISSING);
    }

    public When the_api_is_ready() {
      // The CAPTURE: one awaitReady bounds the API reach AND the controller convergence within the
      // budget, producing the snapshot both phases read. A never-ready API is a false apiReady
      // facet
      // (the edge does not throw), so this phase fails fast and the controllers step is skipped.
      this.snapshot = contact.orElseThrow().awaitReady(kubeconfig, controllers, budget);
      return check(ClusterReadinessPhase.API_READY, snapshot.apiReady(), SymptomKind.API_NOT_READY);
    }

    public When the_required_controllers_are_effective() {
      return check(
          ClusterReadinessPhase.CONTROLLERS_EFFECTIVE,
          snapshot.controllersEffective(),
          SymptomKind.CONTROLLER_NOT_READY);
    }

    /**
     * Record the phase's observation, then fail-fast if not ready: an ok phase records an ok wire
     * and returns; a not-ready phase records a failed wire carrying the typed symptom (the doctor's
     * routing key) and throws so jGiven marks the step FAILED. The map is the material the domain's
     * consult reads.
     */
    private When check(ClusterReadinessPhase phase, boolean ready, SymptomKind failureSymptom) {
      if (ready) {
        observations.put(
            phase,
            new ObservationWire(
                "ok", phase.label(), Optional.empty(), Map.of("phase", phase.name())));
        return self();
      }
      observations.put(
          phase,
          new ObservationWire(
              "failed",
              phase.label() + ": not ready",
              Optional.of(failureSymptom),
              Map.of("phase", phase.name())));
      throw new ClusterNotReadyError(phase, failureSymptom);
    }
  }

  /**
   * Then: the cluster is ready — reached only once every phase passed (a failing phase throws in
   * the When), the readable closing line, not where evaluation happens.
   */
  public static class Then extends Stage<Then> {

    public Then the_cluster_is_ready() {
      return self();
    }
  }
}
