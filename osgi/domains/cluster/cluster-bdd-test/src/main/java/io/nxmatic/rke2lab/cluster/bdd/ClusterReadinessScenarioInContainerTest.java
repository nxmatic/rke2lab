package io.nxmatic.rke2lab.cluster.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.nxmatic.rke2lab.cluster.contract.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.contract.ControllerRef;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.Consultation;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcome;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
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
 * ConsultingService}) — into the SAME registry the scenario resolves from, then plays it
 * in-container through {@link ScenarioPlayer} (the shared play recipe the production {@code
 * GenericRunbookHandler} also drives) and asserts on the harvested {@link ScenarioOutcome}.
 *
 * <p>No seam, no system-export: because the fragment shares the bundle's classloader, the mock this
 * passenger registers is the same {@code Class} the scenario reads (unlike the out-of-container
 * shape, which registers on the host loader and needs the port system-exported). Because the play,
 * the harvest, and this assertion all sit on the same in-container worker, it reads the LIVE
 * outcome — the jGiven {@code ReportModel} as an object, no JSON round-trip (that serialisation is
 * the host-crossing handler's concern, not the test's). Registrations are unregistered in a {@code
 * finally} because the framework is shared across the passenger's tests (an oldest-wins ranking tie
 * would otherwise leak a mock).
 */
public class ClusterReadinessScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  @Test
  void a_healthy_cluster_plays_the_scenario_green() throws Exception {
    final ScenarioOutcome outcome = playWith(new FakeContact(true, true), null);
    final ReportModel runbook = outcome.runbook();

    assertNotNull(runbook, "the player harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a reachable cluster plays every readiness phase green");
    final String narration = narrationOf(runbook);
    assertTrue(narration.contains("kubeconfig"), "the kubeconfig phase is narrated");
    assertTrue(narration.contains("api"), "the api-ready phase is narrated");
    assertTrue(
        outcome.consultations().isEmpty(), "a healthy run raised no symptom, so consults no one");
  }

  @Test
  void an_unready_api_renders_a_failed_scenario() throws Exception {
    final ScenarioOutcome outcome = playWith(new FakeContact(false, true), null);

    assertEquals(
        ExecutionStatus.FAILED,
        outcome.runbook().getScenarios().get(0).getExecutionStatus(),
        "a cluster whose API is not ready fails the checkpoint (fail-fast on the api phase)");
  }

  @Test
  void a_failing_phase_makes_the_domain_consult_the_doctor_itself() throws Exception {
    // Fork B: the checkpoint owns its consult. A failing API phase raises an API_NOT_READY symptom;
    // the scenario resolves the doctor from its OWN registry and consults — the consultation rides
    // the outcome back, the host no longer computes it.
    final RecordingDoctor doctor = new RecordingDoctor();
    final ScenarioOutcome outcome = playWith(new FakeContact(false, true), doctor);

    assertEquals(
        ExecutionStatus.FAILED, outcome.runbook().getScenarios().get(0).getExecutionStatus());
    assertEquals(1, doctor.consultedCheckpoints.size(), "the domain consulted the doctor once");
    assertTrue(
        doctor.consultedCheckpoints.get(0).contains("api-not-ready"),
        "the consult carries the typed symptom the doctor routes on");

    final List<SeedEnvelope> consultations = outcome.consultations();
    assertEquals(1, consultations.size(), "the consultation rides the outcome back to the host");
    assertEquals(
        "cluster-readiness",
        CODEC.decode(consultations.get(0).payload()).path("scenarioId").asText(),
        "the consultation names the checkpoint the host joins on");
  }

  @Test
  void a_survey_run_does_not_probe_and_renders_pending() throws Exception {
    // A pure probe is SURVEY-INERT: it has no honest plan-only shape (its output IS the live
    // state),
    // so under a surveying gate its bodies are SKIPPED — the live contact is never called (no
    // kubectl), and every step renders PENDING. Register a recording contact + a surveying gate and
    // prove both: the probe was not called, and the scenario reads SCENARIO_PENDING.
    final RecordingContact contact = new RecordingContact();
    final BundleContext context = FrameworkUtil.getBundle(ClusterBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(RunGate.class, () -> false, new Hashtable<>()));
    registrations.add(
        context.registerService(ClusterReadinessContact.class, contact, new Hashtable<>()));
    try {
      final ScenarioOutcome outcome =
          new ScenarioPlayer().play(ClusterReadinessScenario.class, store -> {});
      assertTrue(!contact.probed, "a survey-inert probe never contacts the live cluster");
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
  private static ScenarioOutcome playWith(ClusterReadinessContact contact, ConsultingService doctor)
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
      return new ScenarioPlayer().play(ClusterReadinessScenario.class, store -> {});
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

  /** A contact that records whether it was ever asked — proves a survey-inert run never probes. */
  private static final class RecordingContact implements ClusterReadinessContact {
    boolean probed;

    @Override
    public boolean isApiReady(Path kubeconfig) {
      this.probed = true;
      return true;
    }

    @Override
    public boolean areControllersEffective(Path kubeconfig, List<ControllerRef> controllers) {
      this.probed = true;
      return true;
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
