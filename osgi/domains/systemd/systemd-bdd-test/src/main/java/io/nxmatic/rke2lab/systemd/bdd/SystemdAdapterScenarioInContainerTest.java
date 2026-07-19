package io.nxmatic.rke2lab.systemd.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.Consultation;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcome;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.systemd.contract.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.systemd.contract.SystemdStatusSnapshot;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * The in-container proof of the systemd scion, run WHERE the scenario lives (this passenger shares
 * the systemd-bdd host loader through the fragment). It registers the scion's collaborators — a
 * mock {@link SystemdRuntimeProbe} (and, for the failure case, a mock {@link ConsultingService}) —
 * into the SAME registry the scenario resolves from, then plays it in-container through {@link
 * ScenarioPlayer} (the shared play recipe the production {@code GenericRunbookHandler} also drives)
 * and asserts on the harvested {@link ScenarioOutcome}.
 *
 * <p>No seam, no system-export: because the fragment shares the bundle's classloader, the mock this
 * passenger registers is the same {@code Class} the scenario reads (unlike the out-of-container
 * shape, which registers on the host loader and needs the port system-exported). It is the {@code
 * ClusterReadinessScenarioInContainerTest} shape — register in-container, then act, one method —
 * applied to the systemd scenario play. Because the play, the harvest, and this assertion all sit
 * on the same in-container worker, it reads the LIVE outcome — the jGiven {@code ReportModel} as an
 * object, no JSON round-trip (that serialisation is the host-crossing handler's concern).
 * Registrations are unregistered in a {@code finally} because the framework is shared across the
 * passenger's tests (an oldest-wins ranking tie would otherwise leak a mock).
 */
public class SystemdAdapterScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  @Test
  void a_healthy_adapter_plays_the_scenario_green() throws Exception {
    final ScenarioOutcome outcome = playWith(healthy(), null);
    final ReportModel runbook = outcome.runbook();

    assertNotNull(runbook, "the player harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a reachable adapter plays every facet green");
    final String narration = narrationOf(runbook);
    assertTrue(narration.contains("systemd endpoint"), "the endpoint facet is narrated");
    assertTrue(narration.contains("mandatory target"), "the mandatory-target facet is narrated");
    assertTrue(
        outcome.consultations().isEmpty(), "a healthy run raised no symptom, so consults no one");
  }

  @Test
  void an_unhealthy_target_renders_a_failed_scenario() throws Exception {
    final ScenarioOutcome outcome = playWith(unhealthyTarget(), null);

    assertEquals(
        ExecutionStatus.FAILED,
        outcome.runbook().getScenarios().get(0).getExecutionStatus(),
        "an adapter whose mandatory target is unhealthy fails the checkpoint (fail-fast)");
  }

  @Test
  void a_failing_facet_makes_the_domain_consult_the_doctor_itself() throws Exception {
    // Fork B: the checkpoint owns its consult. An unhealthy target raises a CONNECTION_REFUSED
    // symptom; the scenario resolves the doctor from its OWN registry and consults — the
    // consultation rides the outcome back, the host no longer computes it.
    final RecordingDoctor doctor = new RecordingDoctor();
    final ScenarioOutcome outcome = playWith(unhealthyTarget(), doctor);

    assertEquals(
        ExecutionStatus.FAILED, outcome.runbook().getScenarios().get(0).getExecutionStatus());
    assertEquals(1, doctor.consultedCheckpoints.size(), "the domain consulted the doctor once");
    assertTrue(
        doctor.consultedCheckpoints.get(0).contains("connection-refused"),
        "the consult carries the typed symptom the doctor routes on");

    final List<SeedEnvelope> consultations = outcome.consultations();
    assertEquals(1, consultations.size(), "the consultation rides the outcome back to the host");
    assertEquals(
        "systemd-adapter",
        CODEC.decode(consultations.get(0).payload()).path("scenarioId").asText(),
        "the consultation names the checkpoint the host joins on");
  }

  @Test
  void a_survey_run_does_not_probe_and_renders_pending() throws Exception {
    // A pure probe is SURVEY-INERT: its output IS the live dbus/systemd state, so it has no honest
    // plan-only shape. Under a surveying gate its bodies are SKIPPED — the probe is never called
    // (no dbus connection), and every step renders PENDING. Prove both: not probed, and PENDING.
    final RecordingProbe probe = new RecordingProbe();
    final BundleContext context = FrameworkUtil.getBundle(SystemdBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(RunGate.class, () -> false, new Hashtable<>()));
    registrations.add(context.registerService(SystemdRuntimeProbe.class, probe, new Hashtable<>()));
    try {
      final ScenarioOutcome outcome =
          new ScenarioPlayer().play(SystemdAdapterScenario.class, store -> {});
      assertTrue(!probe.probed, "a survey-inert probe never opens a dbus connection");
      assertEquals(
          ExecutionStatus.SCENARIO_PENDING,
          outcome.runbook().getScenarios().get(0).getExecutionStatus(),
          "a surveyed pure probe renders PENDING — it planned nothing, it touched nothing");
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /**
   * Register the mock collaborators into THIS bundle's registry, play the scenario in-container
   * through the shared {@link ScenarioPlayer}, and return its live {@link ScenarioOutcome}.
   * Registrations are removed in the {@code finally} so each test plays against exactly its own
   * mocks.
   */
  private static ScenarioOutcome playWith(SystemdRuntimeProbe probe, ConsultingService doctor)
      throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(SystemdBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(SystemdRuntimeProbe.class, probe, new Hashtable<>()));
    if (doctor != null) {
      registrations.add(
          context.registerService(ConsultingService.class, doctor, new Hashtable<>()));
    }
    try {
      return new ScenarioPlayer().play(SystemdAdapterScenario.class, store -> {});
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** The scenario's narrated lines, joined — what the operator reads in the runbook. */
  private static String narrationOf(ReportModel runbook) {
    return runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps().stream()
        .map(StepModel::getCompleteSentence)
        .reduce("", (a, b) -> a + "\n" + b);
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

  /** A probe that records whether it was ever asked — proves a survey-inert run never probes. */
  private static final class RecordingProbe implements SystemdRuntimeProbe {
    boolean probed;

    @Override
    public SystemdStatusSnapshot probe(
        io.nxmatic.rke2lab.systemd.contract.SystemdProbeRequest request) {
      this.probed = true;
      return SystemdStatusSnapshot.builder()
          .runtimePrecheckReady(true)
          .mandatoryTargetHealthy(true)
          .mandatoryTargetState("active")
          .failedUnits(0)
          .summary("all green")
          .build();
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
