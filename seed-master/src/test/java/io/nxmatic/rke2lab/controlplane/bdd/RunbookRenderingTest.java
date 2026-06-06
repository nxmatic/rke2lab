package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R6: a FAILED node is a first-class render state. Before the doctor exists (Increment A), a failed
 * scenario must render cleanly — FAILED, with the Diagnosis/Mitigation sections simply absent, not
 * a null hole. This de-risks the runbook's failure rendering before any doctor is wired.
 *
 * <p>Plays the systemd-adapter scenario the same way the checkpoint does on failure (the {@code
 * Then} throws, so {@code finished()} is skipped), then renders the shared model and asserts the
 * FAILED scenario survives into a readable runbook.
 */
class RunbookRenderingTest {

  @Test
  void failed_node_records_into_the_shared_model_without_a_doctor(@TempDir Path outputDir) {
    final ReportModel runbook = playFailingScenario();

    // The model itself must carry the FAILED scenario — proving the checkpoint's skipped-finished()
    // failure path still produces a renderable node (the core Increment A risk).
    assertEquals(1, runbook.getScenarios().size());
    assertEquals(ExecutionStatus.FAILED, runbook.getScenarios().get(0).getExecutionStatus());

    new RunbookRenderer(outputDir, message -> {}).render(runbook);

    final Path index = outputDir.resolve("adoc").resolve(RunbookRenderer.INDEX_FILE);
    assertTrue(Files.exists(index), "runbook index should be rendered");

    final String report = readAll(outputDir.resolve("adoc"));
    // FAILED rendered; and the doctor's sections are absent (first-class state, not a null hole).
    assertTrue(
        report.contains("Systemd adapter becomes reachable"),
        "runbook should name the failed scenario");
    assertFalse(report.contains("Diagnosis"), "no Diagnosis section before the doctor exists");
    assertFalse(report.contains("Mitigation"), "no Mitigation section before the doctor exists");
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
