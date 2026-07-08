package io.nxmatic.rke2lab.cluster.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ControllerRef;
import io.nxmatic.rke2lab.jgiven.testkit.JGivenTestkit;
import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;

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

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      JGivenTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // cluster-port is the scenario's DSL vocabulary AND the mock's interface — system-export
          // it from ONE place so the mock the test registers is the class the in-container scenario
          // reads (typed, no ClassCastException across the boundary).
          .systemPackages(
              "io.nxmatic.rke2lab.cluster.port;version=1.0.0", "org.slf4j;version=2.0.0")
          // The JUnit-Platform runner world (launcher + engine) the front-door drives in-container.
          .withJUnitRunner()
          .build();

  @Test
  void a_healthy_cluster_plays_the_scenario_green() throws Exception {
    final ReportModel runbook = playWith(new FakeContact(true, true));

    assertNotNull(runbook, "the front-door harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a reachable cluster plays every readiness phase green");
    final String narration = narrationOf(runbook);
    assertTrue(narration.contains("kubeconfig"), "the kubeconfig phase is narrated");
    assertTrue(narration.contains("api"), "the api-ready phase is narrated");
  }

  @Test
  void an_unready_api_renders_a_failed_scenario() throws Exception {
    final ReportModel runbook = playWith(new FakeContact(false, true));

    assertEquals(
        ExecutionStatus.FAILED,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a cluster whose API is not ready fails the checkpoint (fail-fast on the api phase)");
  }

  /**
   * Boot the bundle + closure, publish {@code contact} as the registry's {@link
   * ClusterReadinessContact}, then play the scenario in-container and rebuild its runbook on the
   * HOST loader. The front-door returns the model as a serialized JSON {@code String} (a jGiven
   * object loaded by the bundle can't be cast on the host loader — the realm boundary); we round it
   * back through {@link ScenarioJsonReader} into a {@code ReportModel} of THIS realm — the same
   * host-side rebuild the cross-world graft performs, in its minimal form.
   */
  private static ReportModel playWith(ClusterReadinessContact contact) throws Exception {
    final Bundle bdd = installClusterBddWithClosure();
    // One framework is shared across the tests (a class-static extension); a lingering mock from a
    // prior play would be the one the scenario resolves (oldest service wins a ranking tie).
    // Register fresh, unregister in the finally, so each test plays against exactly its own
    // contact.
    final var registration =
        felix.context().registerService(ClusterReadinessContact.class, contact, new Hashtable<>());
    try {
      bdd.start();
      final Class<?> runner = bdd.loadClass(RUNNER_FQN);
      final Method run = runner.getMethod("run");
      final String json = (String) run.invoke(null);
      return rebuild(json);
    } finally {
      registration.unregister();
    }
  }

  /** The scenario's narrated lines, joined — what the operator reads in the runbook. */
  private static String narrationOf(ReportModel runbook) {
    return runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps().stream()
        .map(com.tngtech.jgiven.report.model.StepModel::getCompleteSentence)
        .reduce("", (a, b) -> a + "\n" + b);
  }

  /** Rebuild a host-realm {@link ReportModel} from the front-door's serialized JSON. */
  private static ReportModel rebuild(String json) throws Exception {
    final File tmp = Files.createTempFile("cluster-readiness-runbook", ".json").toFile();
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), json);
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
}
