package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A FAILED node is a first-class render state, and the doctor's diagnosis is rendered onto it. A
 * failed scenario renders FAILED; when a {@link ConsultationReport} is recorded for that
 * checkpoint, its Diagnosis (⚕ generalist summary) and Mitigation (℞ prescriptions) appear on the
 * node, joined to the {@code ScenarioModel} by {@link Checkpoint#scenarioTitle()}.
 *
 * <p>Plays the systemd-adapter scenario the same way the checkpoint does on failure (the {@code
 * Then} throws, so {@code finished()} is skipped), then renders the shared model with a
 * consultation log and asserts the doctor's prescription survives into a readable runbook.
 */
class RunbookRenderingTest {

  @Test
  void failed_node_renders_the_doctor_diagnosis(@TempDir Path outputDir) {
    final ReportModel runbook = playFailingScenario();

    // The model itself must carry the FAILED scenario — proving the checkpoint's skipped-finished()
    // failure path still produces a renderable node onto which the doctor's diagnosis is injected.
    assertEquals(1, runbook.getScenarios().size());
    assertEquals(ExecutionStatus.FAILED, runbook.getScenarios().get(0).getExecutionStatus());

    final ConsultationLog consultations = new ConsultationLog();
    consultations.record(
        new ConsultationReport(
            Checkpoint.SYSTEMD_ADAPTER.slug(),
            List.of(
                Dossier.failed(
                    Symptom.CONNECTION_REFUSED,
                    "dbus refused",
                    Map.of("source", "systemd-adapter-probe"))),
            new RemediationPlan(
                Symptom.CONNECTION_REFUSED,
                List.of(
                    Prescription.of(
                        RemediationProgramRef.RESTART_UNIT,
                        Map.of("unit", "rke2lab-systemd-adapter.service"),
                        "restart the systemd-adapter unit")),
                "the dbus-over-TCP endpoint refused the connection")));

    new RunbookRenderer(outputDir, message -> {}).render(runbook, consultations);

    final Path index = outputDir.resolve("adoc").resolve(RunbookRenderer.INDEX_FILE);
    assertTrue(Files.exists(index), "runbook index should be rendered");

    final String report = readAll(outputDir.resolve("adoc"));
    // FAILED rendered; and the doctor's diagnosis is now injected onto the failed node.
    assertTrue(
        report.contains("Systemd adapter becomes reachable"),
        "runbook should name the failed scenario");
    assertTrue(report.contains("Diagnosis"), "the doctor's Diagnosis section should render");
    assertTrue(report.contains("Mitigation"), "the doctor's Mitigation section should render");
    assertTrue(
        report.contains(RemediationProgramRef.RESTART_UNIT.id()),
        "the restart-systemd-unit prescription should render");
  }

  /**
   * Mirrors {@code SystemdAdapterStage.launch()} on failure: the Then throws, but {@code
   * finished()} still runs in a finally so the failed scenario is flushed into the model and
   * renders (the bug this test guards against).
   */
  private static ReportModel playFailingScenario() {
    final ReportModel model = new ReportModel();
    final Scenario<
            SystemdAdapterScenario.Given, SystemdAdapterScenario.When, SystemdAdapterScenario.Then>
        scenario =
            Scenario.create(
                SystemdAdapterScenario.Given.class,
                SystemdAdapterScenario.When.class,
                SystemdAdapterScenario.Then.class);
    scenario.setModel(model);
    scenario.startScenario("systemd adapter becomes reachable");
    try {
      try {
        scenario
            .given()
            .the_seed_node("bioskop-master", config())
            .probed_by(FakeSystemdAdapterProbes.connectionRefused());
        scenario.when().the_systemd_adapter_probe_runs();
        scenario.then().the_dbus_endpoint_responds();
      } finally {
        scenario.finished();
      }
    } catch (Throwable expected) {
      // The scenario fails on purpose (status != ok); finished() already flushed it to the model.
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
    return OperatorConfiguration.mandatory().asBootstrapConfig();
  }
}
