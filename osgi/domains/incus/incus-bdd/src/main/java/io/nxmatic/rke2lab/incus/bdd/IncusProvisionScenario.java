package io.nxmatic.rke2lab.incus.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.contract.ObservationWire;
import io.nxmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.nxmatic.rke2lab.doctor.contract.SymptomKind;
import io.nxmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.nxmatic.rke2lab.incus.contract.ImageBuilder;
import io.nxmatic.rke2lab.incus.contract.IncusExecRequest;
import io.nxmatic.rke2lab.incus.contract.IncusInstanceContact;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioRegistry;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The incus provisioning checkpoint, a production jGiven scenario told in the INCUS DOMAIN's own
 * vocabulary — the seed image built through {@link ImageBuilder}, then the launched instance's
 * reachability probed through {@link IncusInstanceContact}, no host/Pulumi type. Played
 * IN-CONTAINER by the engine so the runbook shows a real node of the OSGi world; it lives in {@code
 * incus-bdd} (only the {@code incus-contract} seam, no {@code incus-core} — the heavy Pulumi-bound
 * provisioning stays host-side), not a {@code -test} fragment (it is live seeding logic).
 *
 * <p>The bbox/systemd/cluster twin: it resolves its collaborators from its OWN bundle's registry
 * ({@link ScenarioRegistry}) — both incus contacts, the ambient {@link RunGate} (whose {@link
 * RunGate#cultivating() cultivating} decides build-for-real vs plan-only), and, on a failure, the
 * doctor's {@link ConsultingService}. The scenario is identical live and in test; only who
 * published the collaborators differs (the live {@code DistrobuilderImageBuilder} + {@code
 * ProcessBuilderIncusInstanceContact} + the host's RunGate, or the mocks a test seeds).
 *
 * <p>Preview inertness — the SCION consults the RunGate: under a closed gate it does NOT build or
 * probe (the real edge would ssh/build/create), it records the plan and the step renders PENDING
 * via E9. The image/instance/exec markers are fixed (the offline mock ignores them; the live
 * config-derived plumbing is the same deferral the cluster/systemd twins make for their kubeconfig
 * / endpoint).
 */
@ExtendWith(JGivenExtension.class)
public class IncusProvisionScenario
    extends ScenarioTestBase<
        IncusProvisionScenario.Given, IncusProvisionScenario.When, IncusProvisionScenario.Then> {

  private static final String NODE = "bioskop-master";

  // The front-door harvests the played model + any consultations off these holders (the same
  // scaffolding as the other scions). Initialized (never null) so the null-hygiene gate stays
  // green.
  private static final AtomicReference<ReportModel> LAST_RUNBOOK = new AtomicReference<>();
  private static final AtomicReference<List<SeedEnvelope>> LAST_CONSULTATIONS =
      new AtomicReference<>(List.of());

  static ReportModel lastRunbook() {
    return Objects.requireNonNull(
        LAST_RUNBOOK.get(), "the scenario has not played yet — no runbook to harvest");
  }

  static List<SeedEnvelope> lastConsultations() {
    return LAST_CONSULTATIONS.get();
  }

  private final Scenario<Given, When, Then> scenario = createScenario();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Test
  void the_instance_is_provisioned() {
    final ImageBuilder imageBuilder = resolveImageBuilder();
    final IncusInstanceContact instanceContact = resolveInstanceContact();
    final RunGate gate = resolveGate();
    // The @Test body OWNS the observation sink (the same discipline as the other scions): the When
    // fills it, and the consult below reads THIS reference — independent of jGiven's stage state
    // after a fail-fast step, so a failed build/probe still reaches the consult.
    final List<ObservationWire> observations = new ArrayList<>();
    given()
        .the_seed_node(NODE)
        .and()
        .provisioned_through(imageBuilder, instanceContact, gate, observations);
    when().the_run_condition_is_read().and().the_image_is_built().and().the_instance_is_reachable();
    then().the_instance_is_provisioned();
    LAST_RUNBOOK.set(getScenario().getModel());
    LAST_CONSULTATIONS.set(consultOnFailure(observations));
  }

  /**
   * The domain consults the doctor ITSELF on a failed build or unreachable instance (fork B: the
   * checkpoint owns its consult, not the host). Any observation carrying a symptom triggers it;
   * resolve the doctor's {@link ConsultingService} from THIS bundle's registry, build an {@code
   * incus-provision} SeedEnvelope around the observations, and consult. A clean provision raised no
   * symptom, so it consults no one (empty list).
   */
  private List<SeedEnvelope> consultOnFailure(List<ObservationWire> observations) {
    final boolean anySymptom = observations.stream().anyMatch(o -> o.symptom().isPresent());
    if (!anySymptom) {
      return List.of();
    }
    return resolveDoctor()
        .map(doctor -> List.of(doctor.consult(consultCheckpoint(observations))))
        .orElseGet(List::of);
  }

  /** The {@code incus-provision} SeedEnvelope the domain hands the doctor — its observations. */
  private static SeedEnvelope consultCheckpoint(List<ObservationWire> observations) {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            Checkpoint.INCUS_PROVISION.slug(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.copyOf(observations));
    final SeedCodec codec = new SeedCodec();
    return SeedEnvelope.of(DoctorCoordinate.READINESS_CHECKPOINT, codec.encode(checkpoint));
  }

  /**
   * The image-build request the scion drives. A fixed marker in the offline scenario (the mock
   * ignores it); the live config-derived path translation is deferred, exactly as the cluster twin
   * defers its kubeconfig.
   */
  private static ImageBuildRequest imageRequest() {
    return new ImageBuildRequest(
        "distrobuilder", "/srv/host/incus-build", "config.yaml", "artifacts", "", "", "", "");
  }

  /** The reachability request the scion probes the launched instance with (fixed marker). */
  private static IncusExecRequest execRequest() {
    return new IncusExecRequest("localhost", "default", NODE);
  }

  private ImageBuilder resolveImageBuilder() {
    return require(
        ImageBuilder.class,
        "no ImageBuilder in the registry (live edge or test mock must publish one)");
  }

  private IncusInstanceContact resolveInstanceContact() {
    return require(
        IncusInstanceContact.class,
        "no IncusInstanceContact in the registry (live edge or test mock must publish one)");
  }

  /**
   * Resolve the ambient {@link RunGate} from THIS bundle's registry — the whole-run live/preview
   * fact. Required: a run without a published gate is a wiring bug (the host publishes it at boot,
   * a test registers a mock), so this fails loud rather than guessing a default.
   */
  private RunGate resolveGate() {
    return require(
        RunGate.class,
        "no RunGate in the registry (the host publishes it at boot, a test registers a mock)");
  }

  private <T> T require(Class<T> type, String message) {
    return ScenarioRegistry.of(this).require(type, message);
  }

  /**
   * Resolve the doctor's {@link ConsultingService} from THIS bundle's registry, or {@link
   * Optional#empty()} if none is published — a real runtime condition (a world booted without the
   * doctor), so a failure without a doctor degrades to no consultation rather than a crash.
   */
  private Optional<ConsultingService> resolveDoctor() {
    return ScenarioRegistry.of(this).optional(ConsultingService.class);
  }

  /** Given: the seed node, both incus contacts, the run gate, and the observation sink. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState ImageBuilder imageBuilder;
    @ProvidedScenarioState IncusInstanceContact instanceContact;
    @ProvidedScenarioState RunGate gate;
    @ProvidedScenarioState List<ObservationWire> observations;

    public Given the_seed_node(@Quoted String name) {
      return self();
    }

    @Hidden
    public Given provisioned_through(
        ImageBuilder imageBuilder,
        IncusInstanceContact instanceContact,
        RunGate gate,
        List<ObservationWire> observations) {
      this.imageBuilder = imageBuilder;
      this.instanceContact = instanceContact;
      this.gate = gate;
      this.observations = observations;
      return self();
    }
  }

  /**
   * When: the scion reads the run condition (the {@link RunGate}), then — only when cultivating —
   * builds the image and probes the instance, recording each facet's {@link ObservationWire} (ok,
   * or failed with a typed {@link SymptomKind}) into the shared sink, fail-fast on the first
   * failure. Under a closed gate it records neither touch (the plan renders PENDING via E9, no edge
   * contacted).
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState ImageBuilder imageBuilder;
    @ExpectedScenarioState IncusInstanceContact instanceContact;
    @ExpectedScenarioState RunGate gate;
    @ExpectedScenarioState List<ObservationWire> observations;
    @ProvidedScenarioState boolean cultivating;

    public When the_run_condition_is_read() {
      this.cultivating = gate.cultivating();
      return self();
    }

    public When the_image_is_built() {
      if (!cultivating) {
        return self();
      }
      final Optional<String> failure = imageBuilder.build(imageRequest());
      if (failure.isPresent()) {
        record("incus image", false, SymptomKind.IMAGE_BUILD_FAILED, failure.get());
        throw new AssertionError("incus image build failed: " + failure.get());
      }
      record("incus image", true, SymptomKind.IMAGE_BUILD_FAILED, null);
      return self();
    }

    public When the_instance_is_reachable() {
      if (!cultivating) {
        return self();
      }
      final Optional<String> failure = instanceContact.isReachable(execRequest());
      if (failure.isPresent()) {
        record("incus instance", false, SymptomKind.INSTANCE_UNREACHABLE, failure.get());
        throw new AssertionError("incus instance unreachable: " + failure.get());
      }
      record("incus instance", true, SymptomKind.INSTANCE_UNREACHABLE, null);
      return self();
    }

    private void record(String facet, boolean ok, SymptomKind failureSymptom, String detail) {
      if (ok) {
        observations.add(
            new ObservationWire("ok", facet, Optional.empty(), Map.of("facet", facet)));
        return;
      }
      observations.add(
          new ObservationWire(
              "failed",
              facet + ": " + (detail == null ? "not ready" : detail),
              Optional.of(failureSymptom),
              Map.of("facet", facet)));
    }
  }

  /**
   * Then: the instance is provisioned — reached only once the image built and the instance answered
   * (a failure throws in the When), the readable closing line, not where evaluation happens.
   */
  public static class Then extends Stage<Then> {

    public Then the_instance_is_provisioned() {
      return self();
    }
  }
}
