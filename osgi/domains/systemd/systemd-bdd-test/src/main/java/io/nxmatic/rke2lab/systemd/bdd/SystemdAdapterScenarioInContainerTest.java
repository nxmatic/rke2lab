package io.nxmatic.rke2lab.systemd.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.Consultation;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.systemd.contract.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.systemd.contract.SystemdStatusSnapshot;
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
 * The in-container proof of the systemd scion, run WHERE the scenario lives (this passenger shares
 * the systemd-bdd host loader through the fragment). It registers the scion's collaborators — a
 * mock {@link SystemdRuntimeProbe} (and, for the failure case, a mock {@link ConsultingService}) —
 * into the SAME registry the scenario resolves from, then plays {@link SystemdBddScenarios#run()}
 * (the production front-door) and asserts on the harvested envelope.
 *
 * <p>No seam, no system-export: because the fragment shares the bundle's classloader, the mock this
 * passenger registers is the same {@code Class} the scenario reads (unlike the out-of-container
 * shape, which registers on the host loader and needs the port system-exported). It is the {@code
 * ClusterReadinessScenarioInContainerTest} shape — register in-container, then act, one method —
 * applied to the systemd front-door play. Registrations are unregistered in a {@code finally}
 * because the framework is shared across the passenger's tests (an oldest-wins ranking tie would
 * otherwise leak a mock).
 */
public class SystemdAdapterScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  @Test
  void a_healthy_adapter_plays_the_scenario_green() throws Exception {
    final String envelope = playWith(healthy(), null);
    final ReportModel runbook = rebuild(envelope);

    assertNotNull(runbook, "the front-door harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a reachable adapter plays every facet green");
    final String narration = narrationOf(runbook);
    assertTrue(narration.contains("systemd endpoint"), "the endpoint facet is narrated");
    assertTrue(narration.contains("mandatory target"), "the mandatory-target facet is narrated");
    assertTrue(
        consultationsOf(envelope).isEmpty(), "a healthy run raised no symptom, so consults no one");
  }

  @Test
  void an_unhealthy_target_renders_a_failed_scenario() throws Exception {
    final String envelope = playWith(unhealthyTarget(), null);

    assertEquals(
        ExecutionStatus.FAILED,
        rebuild(envelope).getScenarios().get(0).getExecutionStatus(),
        "an adapter whose mandatory target is unhealthy fails the checkpoint (fail-fast)");
  }

  @Test
  void a_failing_facet_makes_the_domain_consult_the_doctor_itself() throws Exception {
    // Fork B: the checkpoint owns its consult. An unhealthy target raises a CONNECTION_REFUSED
    // symptom; the scenario resolves the doctor from its OWN registry and consults — the
    // consultation rides the envelope back, the host no longer computes it.
    final RecordingDoctor doctor = new RecordingDoctor();
    final String envelope = playWith(unhealthyTarget(), doctor);

    assertEquals(
        ExecutionStatus.FAILED, rebuild(envelope).getScenarios().get(0).getExecutionStatus());
    assertEquals(1, doctor.consultedCheckpoints.size(), "the domain consulted the doctor once");
    assertTrue(
        doctor.consultedCheckpoints.get(0).contains("connection-refused"),
        "the consult carries the typed symptom the doctor routes on");

    final List<JsonNode> consultations = consultationsOf(envelope);
    assertEquals(1, consultations.size(), "the consultation rides the envelope back to the host");
    assertEquals(
        "systemd-adapter",
        CODEC.decode(consultations.get(0).path("payload").asText()).path("scenarioId").asText(),
        "the consultation names the checkpoint the host joins on");
  }

  /**
   * Register the mock collaborators into THIS bundle's registry, play the scenario in-container
   * through the front-door, and return its serialized envelope. Registrations are removed in the
   * {@code finally} so each test plays against exactly its own mocks.
   */
  private static String playWith(SystemdRuntimeProbe probe, ConsultingService doctor)
      throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(SystemdBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(SystemdRuntimeProbe.class, probe, new Hashtable<>()));
    if (doctor != null) {
      registrations.add(
          context.registerService(ConsultingService.class, doctor, new Hashtable<>()));
    }
    try {
      return SystemdBddScenarios.run(Optional.empty(), List.of());
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
    final File tmp = Files.createTempFile("systemd-adapter-runbook", ".json").toFile();
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), runbookJson);
    return new ScenarioJsonReader().apply(tmp);
  }

  private static SystemdRuntimeProbe healthy() {
    return request ->
        SystemdStatusSnapshot.builder()
            .runtimePrecheckReady(true)
            .mandatoryTargetHealthy(true)
            .mandatoryTargetState("active")
            .failedUnits(0)
            .summary("all green")
            .build();
  }

  private static SystemdRuntimeProbe unhealthyTarget() {
    return request ->
        SystemdStatusSnapshot.builder()
            .runtimePrecheckReady(true)
            .mandatoryTargetHealthy(false)
            .mandatoryTargetState("failed")
            .failedUnits(1)
            .summary("mandatory target failed")
            .build();
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
              Checkpoint.SYSTEMD_ADAPTER.slug(),
              "the systemd adapter checkpoint was consulted",
              "",
              Map.of(),
              List.of());
      return SeedEnvelope.of(DoctorCoordinate.CONSULTATION, CODEC.encode(reply));
    }

    @Override
    public void reviewDrift() {}
  }
}
