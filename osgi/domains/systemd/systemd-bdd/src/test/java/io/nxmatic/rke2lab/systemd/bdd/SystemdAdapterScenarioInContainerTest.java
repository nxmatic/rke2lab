package io.nxmatic.rke2lab.systemd.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.records.Checkpoint;
import io.nxmatic.rke2lab.doctor.records.Consultation;
import io.nxmatic.rke2lab.doctor.records.DoctorCoordinate;
import io.nxmatic.rke2lab.jgiven.testkit.JGivenTestkit;
import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.systemd.port.SystemdStatusSnapshot;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;

/**
 * The out-of-container proof that {@code SystemdAdapterScenario} plays IN-CONTAINER — the systemd
 * twin of {@code ClusterReadinessScenarioInContainerTest}: it boots a real Felix carrying the
 * jGiven + JUnit worlds, installs the {@code systemd-bdd} bundle and its import closure, seeds a
 * MOCK {@link SystemdRuntimeProbe} into the registry (the test decides the snapshot the scenario
 * reads), then drives {@link SystemdBddScenarios#run()} FROM INSIDE the framework through the
 * bundle's own classloader. The harvested {@link ReportModel} is asserted — a healthy snapshot
 * plays green, an unhealthy one renders FAILED and makes the domain consult the doctor.
 *
 * <p>The scenario resolves its collaborator by {@link SystemdRuntimeProbe} from the bundle context
 * — the seam package is system-exported single-exporter, so the mock (registered on the host
 * loader) is the same class the in-container scenario reads (no ClassCastException across the
 * boundary).
 */
@OsgiWorld
class SystemdAdapterScenarioInContainerTest {

  private static final String SYSTEMD_BDD = "(&(type=model)(model=systemd-bdd))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.systemd.bdd.SystemdBddScenarios";
  private static final SeedCodec CODEC = new SeedCodec();

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      JGivenTestkit.felix()
          // The seams the scenario speaks are system-exported — the boot's own posture. A seam
          // (type=seam) is shared FLAT across realms from ONE exporter, so the in-container
          // scenario
          // and the host read the same class: systemd.port (the probe + its snapshot/request),
          // seed.broker.port (the SeedEnvelope + checkpoint vocabulary), doctor.port (the
          // ConsultingService the domain consults).
          .systemPackages(
              "io.nxmatic.rke2lab.systemd.port;version=1.0.0",
              "io.nxmatic.rke2lab.seed.broker.port;version=1.0.0",
              "io.nxmatic.rke2lab.doctor.port;version=1.0.0",
              "org.slf4j;version=2.0.0")
          .withJUnitRunner()
          .build();

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
   * Boot the bundle + closure, publish {@code probe} (and, when non-null, {@code doctor}) into the
   * registry, then play the scenario in-container and return its serialized envelope.
   */
  private static String playWith(SystemdRuntimeProbe probe, ConsultingService doctor)
      throws Exception {
    final Bundle bdd = installSystemdBddWithClosure();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(
        felix.context().registerService(SystemdRuntimeProbe.class, probe, new Hashtable<>()));
    if (doctor != null) {
      registrations.add(
          felix.context().registerService(ConsultingService.class, doctor, new Hashtable<>()));
    }
    try {
      bdd.start();
      final Class<?> runner = bdd.loadClass(RUNNER_FQN);
      final Method run = runner.getMethod("run");
      return (String) run.invoke(null);
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  private static List<JsonNode> consultationsOf(String envelopeJson) {
    final List<JsonNode> consultations = new ArrayList<>();
    CODEC.decode(envelopeJson).path("consultations").forEach(consultations::add);
    return consultations;
  }

  private static String narrationOf(ReportModel runbook) {
    return runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps().stream()
        .map(StepModel::getCompleteSentence)
        .reduce("", (a, b) -> a + "\n" + b);
  }

  private static ReportModel rebuild(String envelopeJson) throws Exception {
    final String runbookJson = CODEC.decode(envelopeJson).path("runbook").asText();
    final File tmp = Files.createTempFile("systemd-adapter-runbook", ".json").toFile();
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), runbookJson);
    return new ScenarioJsonReader().apply(tmp);
  }

  private static Bundle installSystemdBddWithClosure() throws Exception {
    final List<Bundle> installed = new ArrayList<>(felix.installMatching(SYSTEMD_BDD));
    final Bundle bdd = felix.bundle("io.nxmatic.rke2lab.systemd.bdd");
    final List<Bundle> toResolve = new ArrayList<>(installed);
    toResolve.addAll(felix.installImportClosureOf(bdd));
    final boolean resolved = felix.resolve(toResolve);
    if (!resolved) {
      String detail;
      try {
        bdd.start();
        detail = "start() unexpectedly succeeded";
      } catch (Exception ex) {
        detail = ex.toString();
      }
      throw new AssertionError("systemd-bdd did not resolve — " + detail);
    }
    return bdd;
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
   * minimal {@code consultation} SeedEnvelope naming the checkpoint the host joins on.
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
