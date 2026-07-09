package io.nxmatic.rke2lab.cluster.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ControllerRef;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.jgiven.testkit.JGivenTestkit;
import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.nxmatic.rke2lab.seed.broker.codec.DocumentCodec;
import io.nxmatic.rke2lab.seed.broker.port.Checkpoint;
import io.nxmatic.rke2lab.seed.broker.port.Consultation;
import io.nxmatic.rke2lab.seed.broker.port.Coordinate;
import io.nxmatic.rke2lab.seed.broker.port.Document;
import io.nxmatic.rke2lab.seed.broker.port.Domain;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;

/**
 * The out-of-container proof that {@code ClusterReadinessScenario} plays IN-CONTAINER: it boots a
 * real Felix carrying the jGiven + JUnit worlds (via {@link JGivenTestkit#felix()} + {@code
 * withJUnitRunner()}), installs the {@code cluster-bdd} bundle and its import closure, seeds a MOCK
 * {@link ClusterReadinessContact} into the registry (the test decides the outcome — this is the
 * mock-service idiom, not a frozen fragment fake), then drives {@link ClusterBddScenarios#run()}
 * FROM INSIDE the framework through the bundle's own classloader. The harvested {@link ReportModel}
 * is asserted — a healthy mock plays green, a mock that fails the API phase renders FAILED.
 *
 * <p>The scenario is production seeding logic played the same way live and in test; only the
 * registry's content differs. Here the test publishes the mock BEFORE invoking {@code run()}; the
 * scenario resolves it by {@link ClusterReadinessContact} from the bundle context — the seam
 * package is system-exported single-exporter so the mock (registered on the host loader) is the
 * same class the in-container scenario reads (no ClassCastException across the boundary).
 */
@OsgiWorld
class ClusterReadinessScenarioInContainerTest {

  private static final String CLUSTER_BDD = "(&(type=model)(model=cluster-bdd))";
  private static final String RUNNER_FQN = "io.nxmatic.rke2lab.cluster.bdd.ClusterBddScenarios";
  private static final DocumentCodec CODEC = new DocumentCodec();

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      JGivenTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // The three seams the scenario speaks are system-exported — the boot's own posture. A
          // seam (type=seam) is shared FLAT across realms from ONE exporter, never installed as a
          // bundle, so the in-container scenario and the host read the same class (no split, no
          // ClassCastException): cluster.port (DSL + contact), seed.broker.port (the Document
          // envelope + checkpoint/observation vocabulary), doctor.port (the ConsultingService the
          // domain consults). Hand-listed here, derived automatically in the live boot — the DX
          // debt
          // tracked in the seam-gate backlog; a `withSeamsFromDiscovery()` would erase this list.
          .systemPackages(
              "io.nxmatic.rke2lab.cluster.port;version=1.0.0",
              "io.nxmatic.rke2lab.seed.broker.port;version=1.0.0",
              "io.nxmatic.rke2lab.doctor.port;version=1.0.0",
              "org.slf4j;version=2.0.0")
          // The JUnit-Platform runner world (launcher + engine) the front-door drives in-container.
          .withJUnitRunner()
          .build();

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
   * Boot the bundle + closure, publish {@code contact} (and, when non-null, {@code doctor}) into
   * the registry, then play the scenario in-container and return its serialized envelope. The
   * front-door returns the {@code (runbook, consultations)} envelope as ONE JSON String — a jGiven
   * object can't cross the realm boundary live, so everything crosses serialized; the caller
   * derives the {@link ReportModel} and the consultations from it host-side.
   */
  private static String playWith(ClusterReadinessContact contact, ConsultingService doctor)
      throws Exception {
    final Bundle bdd = installClusterBddWithClosure();
    // One framework is shared across the tests (a class-static extension); a lingering mock from a
    // prior play would be the one the scenario resolves (oldest service wins a ranking tie).
    // Register fresh, unregister in the finally, so each test plays against exactly its own mocks.
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(
        felix.context().registerService(ClusterReadinessContact.class, contact, new Hashtable<>()));
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

  /** The consultations the run raised, read off the envelope with the host's own codec. */
  private static List<JsonNode> consultationsOf(String envelopeJson) {
    final List<JsonNode> consultations = new ArrayList<>();
    CODEC.decode(envelopeJson).path("consultations").forEach(consultations::add);
    return consultations;
  }

  /** The scenario's narrated lines, joined — what the operator reads in the runbook. */
  private static String narrationOf(ReportModel runbook) {
    return runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps().stream()
        .map(com.tngtech.jgiven.report.model.StepModel::getCompleteSentence)
        .reduce("", (a, b) -> a + "\n" + b);
  }

  /**
   * Rebuild a host-realm {@link ReportModel} from the front-door's serialized envelope: read the
   * {@code (runbook, consultations)} envelope with the host's own jackson, then round the {@code
   * runbook} field through {@link ScenarioJsonReader} into a model of THIS realm. No jGiven type
   * crosses live — the envelope is flat JSON.
   */
  private static ReportModel rebuild(String envelopeJson) throws Exception {
    final String runbookJson = CODEC.decode(envelopeJson).path("runbook").asText();
    final File tmp = Files.createTempFile("cluster-readiness-runbook", ".json").toFile();
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), runbookJson);
    return new ScenarioJsonReader().apply(tmp);
  }

  /**
   * Install cluster-bdd by its embed capability, plus the sibling/third-party closure it imports.
   */
  private static Bundle installClusterBddWithClosure() throws Exception {
    final List<Bundle> installed = new ArrayList<>(felix.installMatching(CLUSTER_BDD));
    final Bundle bdd = felix.bundle("io.nxmatic.rke2lab.cluster.bdd");
    final List<Bundle> toResolve = new ArrayList<>(installed);
    toResolve.addAll(felix.installImportClosureOf(bdd));
    final boolean resolved = felix.resolve(toResolve);
    if (!resolved) {
      String detail;
      try {
        bdd.start(); // forces resolution and throws a BundleException naming the unmet constraint
        detail = "start() unexpectedly succeeded";
      } catch (Exception ex) {
        detail = ex.toString();
      }
      throw new AssertionError("cluster-bdd did not resolve — " + detail);
    }
    return bdd;
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
   * minimal {@code consultation} Document naming the checkpoint the host joins on. Not a frozen
   * fragment fake: a test-configured service the test seeds into the registry before playing.
   */
  private static final class RecordingDoctor implements ConsultingService {
    final List<String> consultedCheckpoints = new ArrayList<>();

    @Override
    public Document consult(Document checkpoint) {
      consultedCheckpoints.add(checkpoint.payload());
      final Consultation reply =
          new Consultation(
              Checkpoint.CLUSTER_READINESS.slug(),
              "the cluster readiness checkpoint was consulted",
              "",
              Map.of(),
              List.of());
      return new Document(
          Domain.DOCTOR.slug(), Coordinate.CONSULTATION.slug(), CODEC.encode(reply));
    }

    @Override
    public void reviewDrift() {}
  }
}
