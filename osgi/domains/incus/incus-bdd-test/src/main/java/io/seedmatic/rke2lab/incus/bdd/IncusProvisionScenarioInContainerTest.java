package io.seedmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import com.tngtech.jgiven.report.model.StepStatus;
import io.seedmatic.rke2lab.doctor.contract.Checkpoint;
import io.seedmatic.rke2lab.doctor.contract.Consultation;
import io.seedmatic.rke2lab.doctor.contract.ConsultingService;
import io.seedmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.seedmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.seedmatic.rke2lab.incus.contract.ImageBuilder;
import io.seedmatic.rke2lab.incus.contract.IncusCoordinate;
import io.seedmatic.rke2lab.incus.contract.IncusHarvest;
import io.seedmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisRequest;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisResult;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunbookEnvelope;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcome;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.RunGate;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedBroker;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * The in-container proof of the incus scion, run WHERE the scenario lives (this passenger shares
 * the incus-bdd host loader through the fragment). It registers the scion's collaborators — a mock
 * {@link ImageBuilder}, a mock {@link RunGate}, a recording {@link
 * io.seedmatic.rke2lab.seed.broker.port.SeedBroker} (and, for the failure case, a mock {@link
 * ConsultingService}) — into the SAME registry the scenario resolves from, then plays it
 * in-container through {@link ScenarioPlayer} (the shared play recipe the production {@code
 * GenericRunbookHandler} also drives) and asserts on the harvested {@link ScenarioOutcome}. The
 * scion PREPARES the instance (image + manifests); the host makes it grow and probes reachability
 * (Shape C), so no instance contact is registered here.
 *
 * <p>No seam, no system-export: because the fragment shares the bundle's classloader, the mock this
 * passenger registers is the same {@code Class} the scenario reads. The {@code
 * BboxReconciationScenarioInContainerTest} shape applied to the incus scenario play — it reads the
 * LIVE outcome (same in-container worker), no JSON round-trip.
 */
public class IncusProvisionScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  /** The current parcel the host publishes at the GIVEN; the scion files its prep under it. */
  private static final Parcel PARCEL = new Parcel("bioskop", "dev");

  @Test
  void a_live_prepare_builds_and_consults_manifests_green() throws Exception {
    // cultivating() true → the scion builds the image and consults manifests; the mocks succeed. It
    // does NOT probe reachability — the host makes the instance grow and verifies it (Shape C).
    final ScenarioOutcome outcome =
        playWith(cultivatingGate(true), builds(), null, new RecordingCellar());
    final ReportModel runbook = outcome.runbook();

    assertNotNull(runbook, "the player harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a built image + cultivated manifests prepares the instance cleanly");
    assertTrue(
        outcome.consultations().isEmpty(), "a clean prepare raised no symptom, so consults no one");

    // Decision (a): the manifests scion is GRAFTED under its crossing step, so the runbook is
    // faithful — the manifests synthesis renders as a nested sub-tree of "the manifests are
    // cultivated", not a consumed-and-discarded sow. The broker reaps a real manifests
    // RunbookEnvelope
    // (a two-step stub, PASSED here); the graft folds its steps under the rootstock.
    final StepModel cultivated = stepNamed(runbook, "the manifests are cultivated");
    assertFalse(
        cultivated.getNestedSteps().isEmpty(),
        "the manifests scion grafted as a nested sub-tree under its crossing step");

    // The reversal, proven: the SCION harvested AND stored its prep. The store is a cellar-entry on
    // the played model (the scion is a fragment; the host root drains at the boundary), read back
    // through the cellar's OWN generic API — a ScenarioCellar over the LIVE model with an empty
    // durable side, so fetch returns the run's own write (read-your-writes). At the incus-prep
    // coordinate, carrying the recipe digest + the soil the tree was cultivated under (Shape C).
    final ScenarioCellar cellar =
        new ScenarioCellar(() -> runbook, RecordingCellar::new, Optional.empty());
    final IncusHarvest harvest =
        cellar
            .fetch(PARCEL, IncusCoordinate.INCUS_PREP, IncusHarvest.class)
            .orElseThrow(
                () -> new AssertionError("the scion stored no prep harvest at incus-prep"));
    assertEquals(
        "test-recipe",
        harvest.recipeDigest(),
        "the harvest carries the image builder's recipe digest");
  }

  @Test
  void a_survey_run_selects_surveying_and_renders_pending() throws Exception {
    // Mode-blind scion. TOUCH: under a surveying gate the FRONTIER hands it the surveying builder,
    // never the cultivating one (which would shell nix/ssh). Register BOTH halves tagged,
    // and prove the frontier picked surveying. RENDER: every step narrates PENDING.
    final RecordingImageBuilder cultivating = new RecordingImageBuilder();
    final RecordingImageBuilder surveying = new RecordingImageBuilder();
    final BundleContext context = FrameworkUtil.getBundle(IncusBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(
        context.registerService(RunGate.class, cultivatingGate(false), new Hashtable<>()));
    registrations.add(
        context.registerService(ImageBuilder.class, cultivating, gardening("cultivating")));
    registrations.add(
        context.registerService(ImageBuilder.class, surveying, gardening("surveying")));
    registrations.add(
        context.registerService(
            NetplanSynthesisService.class, synthesizesNetwork(), new Hashtable<>()));
    // The broker reaps a PENDING manifests runbook — mirroring prod, where a surveyed manifests
    // scion
    // renders PENDING through its own survey executor. So the grafted sub-tree is pending too, and
    // the
    // whole scenario (its own steps + the graft) stays all-pending → SCENARIO_PENDING.
    registrations.add(
        context.registerService(
            SeedBroker.class, new RecordingBroker(StepStatus.PENDING), new Hashtable<>()));
    registrations.add(
        context.registerService(Cellar.class, new RecordingCellar(), new Hashtable<>()));
    registrations.add(context.registerService(Parcel.class, PARCEL, new Hashtable<>()));
    try {
      final ScenarioOutcome outcome =
          new ScenarioPlayer()
              .play(
                  IncusProvisionScenario.class,
                  IncusProvisionScenario.INPUT.into(IncusRunbookInput.defaults()));
      assertTrue(surveying.built, "the frontier handed the scion the surveying builder");
      assertTrue(
          !cultivating.built, "the frontier never resolved the cultivating builder in survey");
      assertEquals(
          ExecutionStatus.SCENARIO_PENDING,
          outcome.runbook().getScenarios().get(0).getExecutionStatus(),
          "a surveyed scenario renders PENDING — a plan, not a result");
      // Even a surveyed manifests scion is grafted (§ decision a — every scion played is rendered):
      // its pending steps nest under the crossing step, so the runbook stays complete.
      assertFalse(
          stepNamed(outcome.runbook(), "the manifests are cultivated").getNestedSteps().isEmpty(),
          "the surveyed manifests scion still grafts (pending) under its crossing step");
      assertTrue(outcome.consultations().isEmpty(), "a survey raised no symptom");
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** The service properties tagging one half of a mode-sensitive collaborator pair. */
  private static Hashtable<String, Object> gardening(String mode) {
    final Hashtable<String, Object> properties = new Hashtable<>();
    properties.put("rke2lab.gardening", mode);
    return properties;
  }

  @Test
  void a_failed_build_makes_the_domain_consult_the_doctor_itself() throws Exception {
    // A failed image build is a symptom; the scion resolves the doctor from its OWN registry and
    // consults — the consultation rides the envelope back (fork B).
    final RecordingDoctor doctor = new RecordingDoctor();
    final ScenarioOutcome outcome =
        playWith(cultivatingGate(true), failsToBuild(), doctor, new RecordingCellar());

    assertEquals(
        ExecutionStatus.FAILED,
        outcome.runbook().getScenarios().get(0).getExecutionStatus(),
        "a failed image build fails the checkpoint");
    assertEquals(1, doctor.consultedCheckpoints.size(), "the domain consulted the doctor once");
    assertTrue(
        doctor.consultedCheckpoints.get(0).contains("image-build-failed"),
        "the consult carries the typed symptom the doctor routes on");

    final List<SeedEnvelope> consultations = outcome.consultations();
    assertEquals(1, consultations.size(), "the consultation rides the outcome back to the host");
    assertEquals(
        "incus-provision",
        CODEC.decode(consultations.get(0).payload()).path("scenarioId").asText(),
        "the consultation names the checkpoint the host joins on");
  }

  /**
   * Register the mock collaborators into THIS bundle's registry, play the scenario in-container
   * through the shared {@link ScenarioPlayer} — seeding the default activation input through the
   * scenario's {@link IncusProvisionScenario#INPUT} channel, as the handler does — and return its
   * live {@link ScenarioOutcome}. Registrations are removed in the {@code finally} so each test
   * plays against exactly its own mocks.
   */
  private static ScenarioOutcome playWith(
      RunGate gate, ImageBuilder builder, ConsultingService doctor, Cellar cellar)
      throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(IncusBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(RunGate.class, gate, new Hashtable<>()));
    registrations.add(context.registerService(ImageBuilder.class, builder, new Hashtable<>()));
    // The netplan synthesis service the scion resolves to project its network view — mocked here
    // like the ImageBuilder (incus-bdd has no netplan-core; the real @Component is a prod fact).
    // The
    // mock delegates to the real blueprint builder (pure, no bundle) so an amended run gets
    // faithful
    // hwaddrs/dnsmasq; the default (unamended) survey never calls it — the WHEN skips.
    registrations.add(
        context.registerService(
            NetplanSynthesisService.class, synthesizesNetwork(), new Hashtable<>()));
    // The broker incus consults manifests through — mocked here (incus-bdd has no broker-runtime
    // nor
    // manifests; the real routing to the manifests amend/runbook handlers is a fact of prod). The
    // recorder proves incus sowed amend THEN runbook toward manifests.
    registrations.add(
        context.registerService(
            SeedBroker.class, new RecordingBroker(StepStatus.PASSED), new Hashtable<>()));
    // The two ambient facts the scion needs to store its own prep harvest — the host publishes them
    // at the GIVEN in prod; here the passenger seeds a recording cellar and a fixed current parcel.
    registrations.add(context.registerService(Cellar.class, cellar, new Hashtable<>()));
    registrations.add(context.registerService(Parcel.class, PARCEL, new Hashtable<>()));
    if (doctor != null) {
      registrations.add(
          context.registerService(ConsultingService.class, doctor, new Hashtable<>()));
    }
    try {
      return new ScenarioPlayer()
          .play(
              IncusProvisionScenario.class,
              IncusProvisionScenario.INPUT.into(IncusRunbookInput.defaults()));
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** A RunGate the test pins to a chosen cultivating value. */
  private static RunGate cultivatingGate(boolean cultivating) {
    return () -> cultivating;
  }

  /**
   * A NetplanSynthesisService that derives the real {@link ClusterNetworkBlueprint} — pure (no
   * bundle, no I/O), so an amended run gets faithful hwaddrs/dnsmasq. The default survey never
   * calls it (the WHEN skips on an unamended input); it exists so the eager registry resolve
   * succeeds.
   */
  private static NetplanSynthesisService synthesizesNetwork() {
    return new NetplanSynthesisService() {
      @Override
      public String providerId() {
        return "test-netplan-synthesizer";
      }

      @Override
      public NetplanSynthesisResult synthesize(NetplanSynthesisRequest request) {
        return new NetplanSynthesisResult(
            ClusterNetworkBlueprint.builder()
                .cluster(request.clusterName())
                .node(request.nodeName())
                .deriveRecipeModel()
                .build());
      }
    };
  }

  /** An ImageBuilder that builds successfully. */
  private static ImageBuilder builds() {
    return new ImageBuilder() {
      @Override
      public void build(ImageBuildRequest request) {}

      @Override
      public String recipeDigest() {
        return "test-recipe";
      }
    };
  }

  /** An ImageBuilder that reports a failed build (throws with the failure message). */
  private static ImageBuilder failsToBuild() {
    return new ImageBuilder() {
      @Override
      public void build(ImageBuildRequest request) {
        throw new RuntimeException("nix build exited non-zero");
      }

      @Override
      public String recipeDigest() {
        return "test-recipe";
      }
    };
  }

  /**
   * An ImageBuilder that records whether it was called — proves which half the frontier resolved.
   */
  private static final class RecordingImageBuilder implements ImageBuilder {
    boolean built;

    @Override
    public void build(ImageBuildRequest request) {
      this.built = true;
    }

    @Override
    public String recipeDigest() {
      return "test-recipe";
    }
  }

  /**
   * A mock doctor — records each consulted checkpoint's payload (so the test asserts the domain
   * routed the typed symptom) and returns a minimal {@code consultation} SeedEnvelope naming the
   * checkpoint the host joins on. The test seeds it into the registry before playing.
   */
  private static final class RecordingDoctor implements ConsultingService {
    final List<String> consultedCheckpoints = new ArrayList<>();

    @Override
    public SeedEnvelope consult(SeedEnvelope checkpoint) {
      consultedCheckpoints.add(checkpoint.payload());
      final Consultation reply =
          new Consultation(
              Checkpoint.INCUS_PROVISION.slug(),
              "the incus provision checkpoint was consulted",
              "",
              Map.of(),
              List.of());
      return SeedEnvelope.of(DoctorCoordinate.CONSULTATION, CODEC.encode(reply));
    }

    @Override
    public void reviewDrift() {}
  }

  /**
   * A broker that records the coordinates it was sown toward — the twin of the real {@code
   * DefaultSeedBroker} for the test, minus the SCR handler roster. It proves incus consulted
   * manifests through the door (AMEND then RUNBOOK). The AMEND sow is echoed back (the reconciled
   * trigger); the RUNBOOK sow reaps a real manifests {@link RunbookEnvelope} — a two-step stub the
   * incus scion GRAFTS under its crossing step (§ decision a) — so the test exercises the real
   * graft, not a discarded sow. The stub's steps carry {@code manifestsStepStatus}: PASSED for a
   * green run, PENDING for a survey (mirroring prod, where the manifests scion renders through its
   * own survey executor).
   */
  private static final class RecordingBroker implements SeedBroker {
    final List<SeedCoordinate> sown = new ArrayList<>();
    private final StepStatus manifestsStepStatus;

    RecordingBroker(StepStatus manifestsStepStatus) {
      this.manifestsStepStatus = manifestsStepStatus;
    }

    @Override
    public SeedEnvelope sow(SeedCoordinate wanted, Cellar cellar, SeedEnvelope seed) {
      sown.add(wanted);
      if (wanted instanceof RunbookCoordinate) {
        return SeedEnvelope.of(
            wanted,
            CODEC.encode(
                new RunbookEnvelope(manifestsRunbookJson(manifestsStepStatus), List.of())));
      }
      return seed;
    }

    @Override
    public boolean serves(SeedCoordinate wanted) {
      return true;
    }
  }

  /**
   * Fabricate a real manifests runbook — a two-step scenario played to a {@link ReportModel}, then
   * serialised as {@code ScenarioJsonWriter} text (the flat form that crosses the realm, exactly
   * what a production {@code GenericRunbookHandler} reaps). Its steps carry {@code status} so the
   * graft folds a faithful pending (survey) or passed (live) sub-tree under the incus crossing
   * step.
   */
  private static String manifestsRunbookJson(StepStatus status) {
    final ReportModel model = new ReportModel();
    model.setClassName(ManifestsStubStage.class.getSimpleName());
    final Scenario<ManifestsStubStage, ManifestsStubStage, ManifestsStubStage> scenario =
        Scenario.create(ManifestsStubStage.class);
    scenario.setModel(model);
    scenario.startScenario("the manifests are synthesized");
    scenario.getGivenStage().the_manifests_are_synthesized().the_overlay_is_written();
    try {
      scenario.finished();
    } catch (Throwable ignored) {
      // a green stub does not throw; guarded only to mirror the play recipe.
    }
    model
        .getScenarios()
        .get(0)
        .getScenarioCases()
        .get(0)
        .getSteps()
        .forEach(step -> step.setStatus(status));
    return new ScenarioJsonWriter(model).toString();
  }

  /** The named top-level step of the played scenario — the crossing the graft folds under. */
  private static StepModel stepNamed(ReportModel runbook, String name) {
    return runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps().stream()
        .filter(step -> name.equals(step.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no step named '" + name + "' in the runbook"));
  }

  /** A stub manifests scenario — two steps — the broker reaps so the incus graft has a sub-tree. */
  public static class ManifestsStubStage extends Stage<ManifestsStubStage> {
    @As("the manifests are synthesized")
    public ManifestsStubStage the_manifests_are_synthesized() {
      return self();
    }

    @As("the overlay is written")
    public ManifestsStubStage the_overlay_is_written() {
      return self();
    }
  }

  /**
   * The durable read side the scion's injected {@code ScenarioCellar} delegates {@code
   * fetch}/{@code neighbours} to — an empty backend (nothing pre-filed). The scion's {@code store}
   * no longer reaches it: a store is now a cellar-entry TAG on the played model (asserted via
   * {@link ScenarioCellar#entriesOf}), which the host root drains at the boundary. It is registered
   * only so the injected cellar has a durable delegate to resolve.
   */
  private static final class RecordingCellar implements Cellar {

    @Override
    public <T> void store(
        Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity) {}

    @Override
    public <T> List<T> fetch(Parcel parcel, Class<T> type) {
      return List.of();
    }

    @Override
    public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
      return Optional.empty();
    }

    @Override
    public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
      return Optional.empty();
    }

    @Override
    public List<Parcel> neighbours(Parcel parcel) {
      return List.of(parcel);
    }
  }
}
