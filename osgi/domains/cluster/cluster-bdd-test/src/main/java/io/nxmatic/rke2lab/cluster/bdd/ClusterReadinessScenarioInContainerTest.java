package io.nxmatic.rke2lab.cluster.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.nxmatic.rke2lab.cluster.contract.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.contract.ControllerRef;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.Consultation;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * The in-container proof of the cluster scion, run WHERE the scenario lives (this passenger shares
 * the cluster-bdd host loader through the fragment). It registers the scion's collaborators — a
 * mock {@link ClusterReadinessContact} (and, for the failure case, a mock {@link
 * ConsultingService}) — into the SAME registry the scenario resolves from, then plays {@link
 * ClusterBddScenarios#run()} (the production front-door) and asserts on the harvested envelope.
 *
 * <p>No seam, no system-export: because the fragment shares the bundle's classloader, the mock this
 * passenger registers is the same {@code Class} the scenario reads (unlike the out-of-container
 * shape, which registers on the host loader and needs the port system-exported). It is the {@code
 * BboxReconciliationScenarioInContainerTest} shape — register in-container, then act, one method —
 * applied to the cluster front-door play. Registrations are unregistered in a {@code finally}
 * because the framework is shared across the passenger's tests (an oldest-wins ranking tie would
 * otherwise leak a mock).
 */
public class ClusterReadinessScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  @Test
  void a_healthy_cluster_plays_the_scenario_green() throws Exception {
    final String envelope = playWith(new FakeContact(true, true), null);
    final ReportModel runbook = rebuild(envelope);

    assertNotNull(runbook, "the front-door harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a reachable cluster plays every readiness phase green");
    final String narration = narrationOf(runbook);
    assertTrue(narration.contains("kubeconfig"), "the kubeconfig phase is narrated");
    assertTrue(narration.contains("api"), "the api-ready phase is narrated");
    assertTrue(
        consultationsOf(envelope).isEmpty(), "a healthy run raised no symptom, so consults no one");
  }

  @Test
  void an_unready_api_renders_a_failed_scenario() throws Exception {
    final String envelope = playWith(new FakeContact(false, true), null);

    assertEquals(
        ExecutionStatus.FAILED,
        rebuild(envelope).getScenarios().get(0).getExecutionStatus(),
        "a cluster whose API is not ready fails the checkpoint (fail-fast on the api phase)");
  }

  @Test
  void a_failing_phase_makes_the_domain_consult_the_doctor_itself() throws Exception {
    // Fork B: the checkpoint owns its consult. A failing API phase raises an API_NOT_READY symptom;
    // the scenario resolves the doctor from its OWN registry and consults — the consultation rides
    // the envelope back, the host no longer computes it.
    final RecordingDoctor doctor = new RecordingDoctor();
    final String envelope = playWith(new FakeContact(false, true), doctor);

    assertEquals(
        ExecutionStatus.FAILED, rebuild(envelope).getScenarios().get(0).getExecutionStatus());
    assertEquals(1, doctor.consultedCheckpoints.size(), "the domain consulted the doctor once");
    assertTrue(
        doctor.consultedCheckpoints.get(0).contains("api-not-ready"),
        "the consult carries the typed symptom the doctor routes on");

    final List<JsonNode> consultations = consultationsOf(envelope);
    assertEquals(1, consultations.size(), "the consultation rides the envelope back to the host");
    assertEquals(
        "cluster-readiness",
        CODEC.decode(consultations.get(0).path("payload").asText()).path("scenarioId").asText(),
        "the consultation names the checkpoint the host joins on");
  }

  /**
   * Register the mock collaborators into THIS bundle's registry, play the scenario in-container
   * through the front-door, and return its serialized envelope. Registrations are removed in the
   * {@code finally} so each test plays against exactly its own mocks.
   */
  private static String playWith(ClusterReadinessContact contact, ConsultingService doctor)
      throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(ClusterBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(
        context.registerService(ClusterReadinessContact.class, contact, new Hashtable<>()));
    if (doctor != null) {
      registrations.add(
          context.registerService(ConsultingService.class, doctor, new Hashtable<>()));
    }
    try {
      return ClusterBddScenarios.run();
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

  /** The scenario's narrated lines, joined — what the operator reads in the runbook. */
  private static String narrationOf(ReportModel runbook) {
    return runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps().stream()
        .map(StepModel::getCompleteSentence)
        .reduce("", (a, b) -> a + "\n" + b);
  }

  /**
   * Rebuild a host-realm {@link ReportModel} from the front-door's serialized envelope: read the
   * {@code runbook} field with the host's own jackson, then round it through {@link
   * ScenarioJsonReader} into a model of THIS realm. No jGiven type crosses live — the envelope is
   * flat JSON.
   */
  private static ReportModel rebuild(String envelopeJson) throws Exception {
    final String runbookJson = CODEC.decode(envelopeJson).path("runbook").asText();
    final File tmp = Files.createTempFile("cluster-readiness-runbook", ".json").toFile();
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), runbookJson);
    return new ScenarioJsonReader().apply(tmp);
  }

  /** A configurable mock contact — the test decides API-readiness and controller-effectiveness. */
  private record FakeContact(boolean apiReady, boolean controllersEffective)
      implements ClusterReadinessContact {

    @Override
    public boolean isApiReady(Path kubeconfig) {
      return apiReady;
    }

    @Override
    public boolean areControllersEffective(Path kubeconfig, List<ControllerRef> controllers) {
      return controllersEffective;
    }
  }

  /**
   * A mock doctor — the mock-service idiom for {@link ConsultingService}. It records each consulted
   * checkpoint's payload (so the test can assert the domain routed the typed symptom) and returns a
   * minimal {@code consultation} SeedEnvelope naming the checkpoint the host joins on. The test
   * seeds it into the registry before playing.
   */
  private static final class RecordingDoctor implements ConsultingService {
    final List<String> consultedCheckpoints = new ArrayList<>();

    @Override
    public SeedEnvelope consult(SeedEnvelope checkpoint) {
      consultedCheckpoints.add(checkpoint.payload());
      final Consultation reply =
          new Consultation(
              Checkpoint.CLUSTER_READINESS.slug(),
              "the cluster readiness checkpoint was consulted",
              "",
              Map.of(),
              List.of());
      return SeedEnvelope.of(DoctorCoordinate.CONSULTATION, CODEC.encode(reply));
    }

    @Override
    public void reviewDrift() {}
  }
}
