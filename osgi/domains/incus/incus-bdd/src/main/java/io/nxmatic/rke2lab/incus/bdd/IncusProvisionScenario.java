package io.nxmatic.rke2lab.incus.bdd;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import io.nxmatic.rke2lab.incus.contract.IncusHarvest;
import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioRegistry;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedBroker;
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
 * vocabulary — it PREPARES the instance's material: the seed image built through {@link
 * ImageBuilder} and the manifests tree cultivated by consulting the manifests scion through the
 * broker, no host/Pulumi type. It does NOT make the instance grow nor probe its reachability:
 * creating the instance is the {@code com.pulumi} graph, which stays HOST (Shape C — the scion asks
 * the host to grow it through the broker), and verifying reachability is the responsibility of
 * whoever grows it (the host, post-push). Played IN-CONTAINER by the engine so the runbook shows a
 * real node of the OSGi world; it lives in {@code incus-bdd} (only the {@code incus-contract} seam,
 * no {@code incus-core} — the heavy Pulumi-bound provisioning stays host-side), not a {@code -test}
 * fragment (it is live seeding logic).
 *
 * <p>The bbox/systemd/cluster twin: it resolves its collaborators from its OWN bundle's registry
 * ({@link ScenarioRegistry}) — the {@link ImageBuilder}, the {@link
 * io.nxmatic.rke2lab.seed.broker.port.SeedBroker} (to consult manifests), the ambient {@link
 * RunGate} (whose {@link RunGate#cultivating() cultivating} decides build-for-real vs plan-only),
 * and, on a failure, the doctor's {@link ConsultingService}. The scenario is identical live and in
 * test; only who published the collaborators differs (the live {@code DistrobuilderImageBuilder} +
 * the real broker + the host's RunGate, or the mocks a test seeds).
 *
 * <p>Preview inertness — the SCION consults the RunGate: under a closed gate it does NOT build the
 * image nor consult manifests (the real edge would ssh/build), it records the plan and the step
 * renders PENDING via E9. The image markers are fixed (the offline mock ignores them; the live
 * config-derived plumbing is the same deferral the cluster/systemd twins make for their kubeconfig
 * / endpoint).
 */
@ExtendWith(JGivenExtension.class)
public class IncusProvisionScenario
    extends ScenarioTestBase<
        IncusProvisionScenario.Given, IncusProvisionScenario.When, IncusProvisionScenario.Then> {

  private static final String NODE = "bioskop-master";

  // The handler seeds the activation input here before the launcher plays (same-loader static, the
  // input twin of the manifests scion's INPUT); it carries the @Amendment(SOIL) the scenario
  // forwards to the manifests scion it consults. Initialized (never null) so the null-hygiene gate
  // stays green.
  private static final AtomicReference<IncusRunbookInput> INPUT =
      new AtomicReference<>(IncusRunbookInput.defaults());

  // The front-door harvests the played model + any consultations off these holders (the same
  // scaffolding as the other scions). Initialized (never null) so the null-hygiene gate stays
  // green.
  private static final AtomicReference<ReportModel> LAST_RUNBOOK = new AtomicReference<>();
  private static final AtomicReference<List<SeedEnvelope>> LAST_CONSULTATIONS =
      new AtomicReference<>(List.of());

  /** The handler sets the sown input here before selecting this class into the launcher. */
  public static void seedInput(IncusRunbookInput input) {
    INPUT.set(input);
  }

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
  void the_instance_is_prepared() {
    final ImageBuilder imageBuilder = resolveImageBuilder();
    final RunGate gate = resolveGate();
    final SeedBroker broker = resolveBroker();
    final String soil = INPUT.get().materializationRoot();
    // The @Test body OWNS the observation sink (the same discipline as the other scions): the When
    // fills it, and the consult below reads THIS reference — independent of jGiven's stage state
    // after a fail-fast step, so a failed build still reaches the consult.
    final List<ObservationWire> observations = new ArrayList<>();
    given()
        .the_seed_node(NODE)
        .and()
        .prepared_through(imageBuilder, gate, observations)
        .and()
        .consulting_manifests_through(broker, soil);
    when()
        .the_run_condition_is_read()
        .and()
        .the_image_is_built()
        .and()
        .the_manifests_are_cultivated();
    then()
        .the_instance_is_prepared()
        .and()
        .the_prep_is_stored(imageBuilder, soil, resolveCellar(), resolveParcel());
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

  private ImageBuilder resolveImageBuilder() {
    return require(
        ImageBuilder.class,
        "no ImageBuilder in the registry (live edge or test mock must publish one)");
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

  /**
   * Resolve the {@link SeedBroker} from THIS bundle's registry — the door incus sows toward to
   * consult the manifests scion (amend → runbook). Required: the broker is an SCR
   * {@code @Component} the framework publishes; a run without it is a wiring bug (a test registers
   * a mock broker).
   */
  private SeedBroker resolveBroker() {
    return require(
        SeedBroker.class,
        "no SeedBroker in the registry (the framework publishes DefaultSeedBroker; a test mocks it)");
  }

  /**
   * Resolve the {@link Cellar} the host laid into the registry — the neutral furniture the scion
   * stores its prep harvest at. Required: the host publishes it at the GIVEN, a test registers a
   * mock (the twin of the bbox scion's resolve).
   */
  private Cellar resolveCellar() {
    return require(
        Cellar.class,
        "no Cellar in the registry (the host lays it in at boot, a test registers a mock)");
  }

  /**
   * Resolve the current {@link Parcel} — the one plot this run cultivates, published as an ambient
   * fact beside the Cellar (the twin of the RunGate). The scion stores under it without ever
   * computing the stack identity.
   */
  private Parcel resolveParcel() {
    return require(
        Parcel.class,
        "no current Parcel in the registry (the host publishes it at the GIVEN like the RunGate)");
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

  /** Given: the seed node, the image builder, the run gate, the observation sink, and the door. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState ImageBuilder imageBuilder;
    @ProvidedScenarioState RunGate gate;
    @ProvidedScenarioState List<ObservationWire> observations;
    @ProvidedScenarioState SeedBroker broker;
    @ProvidedScenarioState String soil;

    public Given the_seed_node(@Quoted String name) {
      return self();
    }

    @Hidden
    public Given prepared_through(
        ImageBuilder imageBuilder, RunGate gate, List<ObservationWire> observations) {
      this.imageBuilder = imageBuilder;
      this.gate = gate;
      this.observations = observations;
      return self();
    }

    @Hidden
    public Given consulting_manifests_through(SeedBroker broker, String soil) {
      this.broker = broker;
      this.soil = soil;
      return self();
    }
  }

  /**
   * When: the scion reads the run condition (the {@link RunGate}), builds the image ONLY when
   * cultivating (an edge effect — distrobuilder/ssh), and ALWAYS consults manifests, recording each
   * facet's {@link ObservationWire} (ok, or failed with a typed {@link SymptomKind}) into the
   * shared sink, fail-fast on the first failure. It PREPARES the instance's material (image +
   * manifests); the host makes the instance grow and verifies its reachability (Shape C — the gRPC
   * push and its post-push probe are host-side).
   *
   * <p>The gate splits by NATURE of effect (§ host-cellar-realisation, Live vs preview): the image
   * build is an edge effect, so a closed gate builds nothing (the plan renders PENDING via E9, no
   * edge contacted); the manifests synthesis is a pure FS materialisation into {@code
   * host.N.staging.d}, inert against the live instance, so it runs at preview too — a preview run
   * materialises its staging replica and its host-manifest (traceable from the cellar), only the
   * rsync into {@code host.live.d} is gated (I6). So consulting manifests is NOT gated.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState ImageBuilder imageBuilder;
    @ExpectedScenarioState RunGate gate;
    @ExpectedScenarioState List<ObservationWire> observations;
    @ExpectedScenarioState SeedBroker broker;
    @ExpectedScenarioState String soil;
    @ProvidedScenarioState boolean cultivating;

    private final SeedCodec codec = new SeedCodec();

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

    /**
     * incus consults the manifests scion through the broker — the first metier scion→scion. The
     * instance mounts a materialised tree, which manifests cultivates; incus forwards its OWN
     * {@code @Amendment(SOIL)} as the manifests SOIL (a plot, never a fingerprint — a harvest is
     * fetched, not pushed). Two sows: AMEND reconciles the neutral role into the manifests input at
     * the door (incus names no manifests field), then RUNBOOK plays the synthesis with the amended
     * input. NOT gated: the synthesis writes {@code host.N.staging.d}, a pure FS materialisation
     * the host-tree model wants at preview too (only the rsync into {@code host.live.d} is gated).
     * The manifests scion picks a temp dir itself when the SOIL is blank (a bare survey).
     */
    public When the_manifests_are_cultivated() {
      // AMEND: hand the broker {soil → path} by neutral role; the manifests amend reflector binds
      // it
      // onto ManifestsRunbookInput and returns the reconciled input, still under the runbook
      // coordinate.
      final ObjectNode roleValues = JsonNodeFactory.instance.objectNode();
      roleValues.put(Amendment.SOIL, soil);
      final SeedEnvelope amended =
          broker.sow(
              new AmendCoordinate("manifests"),
              new SeedEnvelope("manifests", "runbook", codec.encode(roleValues)));
      // RUNBOOK: play the manifests synthesis with the reconciled input; the fresh tree is the
      // graft
      // the instance will mount (consumed at once, never cellared — cultivated fresh). No
      // observation
      // recorded here: the consult sink is for probe symptoms (build/reachability), not for the
      // sub-scenario's own outcome — a manifests failure surfaces as the sow throwing.
      broker.sow(new RunbookCoordinate("manifests"), amended);
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
   * Then: the instance's material is prepared — reached only once the image built and manifests
   * were cultivated (a failure throws in the When). The readable closing line; the host then makes
   * the instance grow (Shape C) and verifies its reachability, not this scion.
   */
  public static class Then extends Stage<Then> {

    private final SeedCodec codec = new SeedCodec();

    public Then the_instance_is_prepared() {
      return self();
    }

    /**
     * The scion harvests AND stores — the reversal made concrete (§ host-cellar-realisation,
     * every-scion-contributes), the twin of the bbox scion's {@code the_harvest_is_stored}. It
     * folds the prep INTENTION into an {@link IncusHarvest} — the {@link
     * ImageBuilder#recipeDigest() recipe digest} (stable across a closed gate, the host's
     * image-cache key) and the {@code soil} the manifests tree was cultivated under — and stores it
     * at the {@code incus-prep} coordinate under the current {@link Parcel}. On the Pulumi
     * realisation this store PRODUCES the incus-prep resource; the host FETCHES the harvest and
     * grows the instance (Shape C). The store is unconditional: the cellar consults the RunGate
     * itself to route conserve ({@code up}) vs pre-reserve ({@code preview}), so the scion never
     * picks the mode.
     */
    public Then the_prep_is_stored(
        @Hidden ImageBuilder imageBuilder,
        @Hidden String soil,
        @Hidden Cellar cellar,
        @Hidden Parcel parcel) {
      final IncusHarvest harvest = new IncusHarvest(imageBuilder.recipeDigest(), soil);
      cellar.store(parcel, new SeedEnvelope("incus", "incus-prep", codec.encode(harvest)));
      return self();
    }
  }
}
