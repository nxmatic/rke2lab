package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.config.ConfigLoader;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Increment C: the cluster-readiness checkpoint plays the systemd-adapter scenario nested (the
 * follow-the-chain dependency edge) and renders as a two-tier runbook. An ordered fake incident on
 * one nested phase produces a targeted runbook, and the doctor diagnoses the failing phase's
 * symptom — proving the pattern scales to a second checkpoint with the same machinery.
 */
class NestedRunbookTest {

  @Test
  void cluster_readiness_renders_with_the_systemd_adapter_dependency_nested(@TempDir Path out) {
    final ReportModel runbook = playClusterReadiness(FakeClusterReadinessProbes.allPhasesReady());

    assertEquals(1, runbook.getScenarios().size());
    assertEquals(ExecutionStatus.SUCCESS, runbook.getScenarios().get(0).getExecutionStatus());

    new RunbookRenderer(out, message -> {}).render(runbook);
    final String report = readAll(out.resolve("adoc"));

    // The cluster checkpoint and its nested systemd-adapter dependency both appear — the two-tier
    // DAG the operator walks from a failure up to its root cause.
    assertTrue(report.contains("Cluster becomes ready"), "cluster scenario should render");
    assertTrue(
        report.toLowerCase().contains("systemd adapter dependency"),
        "the nested systemd-adapter dependency step should render under the cluster scenario");
  }

  @Test
  void ordered_fake_incident_on_a_nested_phase_yields_a_targeted_runbook_and_diagnosis(
      @TempDir Path out) {
    // Order an incident at the api-ready phase; the dependency and the kubeconfig phase pass.
    final ClusterReadinessProbe simulated =
        SimulatedClusterReadinessProbe.failingAt(ClusterReadinessPhase.API_READY, Symptom.TIMEOUT);

    final ReportModel model = playClusterReadiness(simulated);

    // The targeted incident makes the cluster scenario FAIL — a targeted runbook.
    assertEquals(ExecutionStatus.FAILED, model.getScenarios().get(0).getExecutionStatus());

    // The failing phase's dossier carries the typed symptom the probe emitted; the doctor consults
    // it. (In production the stage captures this dossier via the same probe-holder seam as the
    // systemd-adapter checkpoint; here the simulation is the source of truth for what failed.)
    final Dossier failing = simulated.probe(config(), ClusterReadinessPhase.API_READY);
    assertEquals(Optional.of(Symptom.TIMEOUT), failing.symptom());

    final Generalist generalist =
        new Generalist(List.of(new DbusTcpSpecialist(config()), new FakeNetworkSpecialist()));
    final RemediationPlan plan = generalist.consult(failing.symptom().orElseThrow(), failing);
    assertTrue(
        plan.hasPrescriptions(), "timeout routes to network; the network specialist treats it");
    assertEquals(
        RemediationProgramRef.CHECK_CONNECTIVITY,
        plan.primaryPrescription().orElseThrow().programRef());

    new RunbookRenderer(out, message -> {}).render(model);
    final String report = readAll(out.resolve("adoc"));
    assertTrue(report.contains("Cluster becomes ready"));
    assertFalse(
        report.contains("Diagnosis"), "node-level Diagnosis section is Increment C+ (deferred)");
  }

  /** A stand-in network specialist so a TIMEOUT (routed to NETWORK) yields a prescription. */
  private static final class FakeNetworkSpecialist implements Specialist {
    @Override
    public SpecialistDomain domain() {
      return SpecialistDomain.NETWORK;
    }

    @Override
    public Optional<Prescription> diagnose(Symptom symptom, Dossier dossier) {
      return Optional.of(
          Prescription.of(
              RemediationProgramRef.CHECK_CONNECTIVITY,
              Map.of("symptom", symptom.id()),
              "check connectivity to the API endpoint"));
    }
  }

  private static ReportModel playClusterReadiness(ClusterReadinessProbe phaseProbe) {
    final ReportModel model = new ReportModel();
    final Scenario<GivenClusterReadiness, WhenClusterReadiness, ThenClusterReadiness> scenario =
        Scenario.create(
            GivenClusterReadiness.class, WhenClusterReadiness.class, ThenClusterReadiness.class);
    scenario.setModel(model);
    scenario.startScenario("cluster becomes ready");
    try {
      try {
        scenario
            .given()
            .the_cluster("nikopol", config())
            .with_phase_probe(phaseProbe)
            .depending_on_systemd_adapter(FakeSystemdAdapterProbes.reachable());
        scenario.when().the_systemd_adapter_dependency_is_satisfied().the_readiness_phases_run();
        scenario.then().the_cluster_is_ready();
      } finally {
        scenario.finished();
      }
    } catch (Throwable expected) {
      // simulated failure path
    }
    return model;
  }

  private static String readAll(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      final StringBuilder sb = new StringBuilder();
      walk.filter(Files::isRegularFile)
          .forEach(
              p -> {
                try {
                  sb.append(Files.readString(p)).append('\n');
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
      return sb.toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static BootstrapConfig config() {
    final Map<String, Map<String, Object>> sections =
        Map.of(
            "incus", Map.of("configDir", "/tmp/rke2lab-bdd-incus"),
            "image", Map.of("sharedFolder", "/tmp/rke2lab-bdd-shared"),
            "worktree", Map.of("dir", "/tmp/rke2lab-bdd-worktree"));
    final Rke2labConfig dto =
        Rke2labConfig.from(ConfigLoader.of(section -> Optional.ofNullable(sections.get(section))));
    return BootstrapConfig.from(dto);
  }
}
