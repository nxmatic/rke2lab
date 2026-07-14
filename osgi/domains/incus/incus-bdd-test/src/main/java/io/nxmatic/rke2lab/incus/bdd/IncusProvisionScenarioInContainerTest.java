package io.nxmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.Consultation;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.nxmatic.rke2lab.incus.contract.ImageBuilder;
import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedBroker;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.io.File;
import java.nio.file.Files;
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
 * io.nxmatic.rke2lab.seed.broker.port.SeedBroker} (and, for the failure case, a mock {@link
 * ConsultingService}) — into the SAME registry the scenario resolves from, then plays {@link
 * IncusBddScenarios#run} (the production front-door) and asserts on the harvested envelope. The
 * scion PREPARES the instance (image + manifests); the host makes it grow and probes reachability
 * (Shape C), so no instance contact is registered here.
 *
 * <p>No seam, no system-export: because the fragment shares the bundle's classloader, the mock this
 * passenger registers is the same {@code Class} the scenario reads. The {@code
 * BboxReconciliationScenarioInContainerTest} shape applied to the incus front-door play.
 */
public class IncusProvisionScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  @Test
  void a_live_prepare_builds_and_consults_manifests_green() throws Exception {
    // cultivating() true → the scion builds the image and consults manifests; the mocks succeed. It
    // does NOT probe reachability — the host makes the instance grow and verifies it (Shape C).
    final String envelope = playWith(cultivatingGate(true), builds(), null);
    final ReportModel runbook = rebuild(envelope);

    assertNotNull(runbook, "the front-door harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a built image + cultivated manifests prepares the instance cleanly");
    assertTrue(
        consultationsOf(envelope).isEmpty(),
        "a clean prepare raised no symptom, so consults no one");
  }

  @Test
  void a_preview_run_builds_nothing() throws Exception {
    // cultivating() false → the scion builds NOTHING; the mock records whether it was called, and
    // must not have been.
    final RecordingImageBuilder builder = recordingBuilder();
    final String envelope = playWith(cultivatingGate(false), builder, null);

    assertEquals(
        ExecutionStatus.SUCCESS,
        rebuild(envelope).getScenarios().get(0).getExecutionStatus(),
        "a preview run plans cleanly without touching the incus host");
    assertTrue(!builder.built, "under a closed gate the scion must NOT build the image");
    assertTrue(consultationsOf(envelope).isEmpty(), "a preview raised no symptom");
  }

  @Test
  void a_failed_build_makes_the_domain_consult_the_doctor_itself() throws Exception {
    // A failed image build is a symptom; the scion resolves the doctor from its OWN registry and
    // consults — the consultation rides the envelope back (fork B).
    final RecordingDoctor doctor = new RecordingDoctor();
    final String envelope = playWith(cultivatingGate(true), failsToBuild(), doctor);

    assertEquals(
        ExecutionStatus.FAILED,
        rebuild(envelope).getScenarios().get(0).getExecutionStatus(),
        "a failed image build fails the checkpoint");
    assertEquals(1, doctor.consultedCheckpoints.size(), "the domain consulted the doctor once");
    assertTrue(
        doctor.consultedCheckpoints.get(0).contains("image-build-failed"),
        "the consult carries the typed symptom the doctor routes on");

    final List<JsonNode> consultations = consultationsOf(envelope);
    assertEquals(1, consultations.size(), "the consultation rides the envelope back to the host");
    assertEquals(
        "incus-provision",
        CODEC.decode(consultations.get(0).path("payload").asText()).path("scenarioId").asText(),
        "the consultation names the checkpoint the host joins on");
  }

  /**
   * Register the mock collaborators into THIS bundle's registry, play the scenario in-container
   * through the front-door, and return its serialized envelope. Registrations are removed in the
   * {@code finally} so each test plays against exactly its own mocks.
   */
  private static String playWith(RunGate gate, ImageBuilder builder, ConsultingService doctor)
      throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(IncusBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(RunGate.class, gate, new Hashtable<>()));
    registrations.add(context.registerService(ImageBuilder.class, builder, new Hashtable<>()));
    // The broker incus consults manifests through — mocked here (incus-bdd has no broker-runtime
    // nor
    // manifests; the real routing to the manifests amend/runbook handlers is a fact of prod). The
    // recorder proves incus sowed amend THEN runbook toward manifests.
    registrations.add(
        context.registerService(SeedBroker.class, new RecordingBroker(), new Hashtable<>()));
    if (doctor != null) {
      registrations.add(
          context.registerService(ConsultingService.class, doctor, new Hashtable<>()));
    }
    try {
      return IncusBddScenarios.run(IncusRunbookInput.defaults());
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** The consultations the run raised, read off the envelope with the host's own codec. */
  private static List<JsonNode> consultationsOf(String envelopeJson) {
    final List<JsonNode> consultations = new ArrayList<>();
    CODEC.decode(envelopeJson).path("consultations").forEach(consultations::add);
    return consultations;
  }

  /**
   * Rebuild a host-realm {@link ReportModel} from the front-door's serialized envelope: read the
   * {@code runbook} field with the host's own jackson, then round it through {@link
   * ScenarioJsonReader} into a model of THIS realm. No jGiven type crosses live — the envelope is
   * flat JSON.
   */
  private static ReportModel rebuild(String envelopeJson) throws Exception {
    final String runbookJson = CODEC.decode(envelopeJson).path("runbook").asText();
    final File tmp = Files.createTempFile("incus-provision-runbook", ".json").toFile();
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), runbookJson);
    return new ScenarioJsonReader().apply(tmp);
  }

  /** A RunGate the test pins to a chosen cultivating value. */
  private static RunGate cultivatingGate(boolean cultivating) {
    return () -> cultivating;
  }

  /** An ImageBuilder that builds successfully. */
  private static ImageBuilder builds() {
    return new ImageBuilder() {
      @Override
      public Optional<String> build(ImageBuildRequest request) {
        return Optional.empty();
      }

      @Override
      public String recipeDigest() {
        return "test-recipe";
      }
    };
  }

  /** An ImageBuilder that reports a failed build (present = the failure summary). */
  private static ImageBuilder failsToBuild() {
    return new ImageBuilder() {
      @Override
      public Optional<String> build(ImageBuildRequest request) {
        return Optional.of("distrobuilder exited non-zero");
      }

      @Override
      public String recipeDigest() {
        return "test-recipe";
      }
    };
  }

  private static RecordingImageBuilder recordingBuilder() {
    return new RecordingImageBuilder();
  }

  /** An ImageBuilder that records whether it was called — proves preview inertness. */
  private static final class RecordingImageBuilder implements ImageBuilder {
    boolean built;

    @Override
    public Optional<String> build(ImageBuildRequest request) {
      this.built = true;
      return Optional.empty();
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
   * manifests through the door (AMEND then RUNBOOK); it echoes the seed back (the scenario does not
   * consume the manifests runbook on this path — the tree is a fresh graft, used by the instance).
   */
  private static final class RecordingBroker implements SeedBroker {
    final List<SeedCoordinate> sown = new ArrayList<>();

    @Override
    public SeedEnvelope sow(SeedCoordinate wanted, SeedEnvelope seed) {
      sown.add(wanted);
      return seed;
    }
  }
}
