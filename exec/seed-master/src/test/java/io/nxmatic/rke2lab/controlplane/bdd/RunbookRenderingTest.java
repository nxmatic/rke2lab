package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.seed.broker.codec.DocumentCodec;
import io.nxmatic.rke2lab.seed.broker.port.Checkpoint;
import io.nxmatic.rke2lab.seed.broker.port.Consultation;
import io.nxmatic.rke2lab.seed.broker.port.Coordinate;
import io.nxmatic.rke2lab.seed.broker.port.Document;
import io.nxmatic.rke2lab.seed.broker.port.Domain;
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
 * failed scenario renders FAILED; when a consultation {@link Document} is recorded for that
 * checkpoint, the OSGi-rendered {@code diagnosisAdoc} text appears on the node, joined to the
 * {@code ScenarioModel} by {@link Checkpoint#scenarioTitle()}. The doctor reasons OSGi-side now, so
 * the runbook reads the already-rendered AsciiDoc string — it builds no {@code RemediationPlan}
 * itself.
 *
 * <p>Plays the systemd-adapter scenario the same way the checkpoint does on failure (the {@code
 * Then} throws, so {@code finished()} is skipped), then renders the shared model with a
 * consultation log of Documents and asserts the diagnosis text survives into a readable runbook.
 */
class RunbookRenderingTest {

  private static final DocumentCodec CODEC = new DocumentCodec();

  @Test
  void failed_node_renders_the_doctor_diagnosis(@TempDir Path outputDir) {
    final ReportModel runbook = playFailingScenario();

    // The model itself must carry the FAILED scenario — proving the checkpoint's skipped-finished()
    // failure path still produces a renderable node onto which the doctor's diagnosis is injected.
    assertEquals(1, runbook.getScenarios().size());
    assertEquals(ExecutionStatus.FAILED, runbook.getScenarios().get(0).getExecutionStatus());

    final String diagnosisAdoc =
        "⚕ Diagnosis: the dbus-over-TCP endpoint refused the connection"
            + "\n\n🔬 Assessment (dbus-tcp/connection-refused/v1): dbus-TCP endpoint refused the"
            + " connection"
            + "\n\n℞ Mitigation ("
            + "restart-unit"
            + "): restart the systemd-adapter unit";
    final ConsultationLog consultations = new ConsultationLog();
    consultations.record(consultationDocument(Checkpoint.SYSTEMD_ADAPTER.slug(), diagnosisAdoc));

    new RunbookRenderer(outputDir, message -> {}).render(runbook, consultations);

    final Path index = outputDir.resolve("adoc").resolve(RunbookRenderer.INDEX_FILE);
    assertTrue(Files.exists(index), "runbook index should be rendered");

    final String report = readAll(outputDir.resolve("adoc"));
    // FAILED rendered; and the doctor's OSGi-rendered diagnosis is now injected onto the failed
    // node.
    assertTrue(
        report.contains("Systemd adapter becomes reachable"),
        "runbook should name the failed scenario");
    assertTrue(report.contains("Diagnosis"), "the doctor's Diagnosis section should render");
    assertTrue(report.contains("Assessment"), "the doctor's Assessment section should render");
    assertTrue(
        report.contains("dbus-TCP endpoint refused the connection"),
        "the assessment summary should render");
    assertTrue(report.contains("Mitigation"), "the doctor's Mitigation section should render");
    assertTrue(
        report.contains("restart-unit"), "the restart-systemd-unit prescription should render");
  }

  @Test
  void declined_reply_renders_a_why_not_silence(@TempDir Path outputDir) {
    final ReportModel runbook = playFailingScenario();

    final String diagnosisAdoc =
        "⚕ Diagnosis: consulted [NETWORK]; assessment only"
            + "\n\n🔬 Assessment (network/reachability/v1): endpoint unreachable at the TCP layer;"
            + " no network-level remediation — the listener is down, not the path";
    final ConsultationLog consultations = new ConsultationLog();
    consultations.record(consultationDocument(Checkpoint.SYSTEMD_ADAPTER.slug(), diagnosisAdoc));

    new RunbookRenderer(outputDir, message -> {}).render(runbook, consultations);

    final String rendered = readAll(outputDir.resolve("adoc"));
    assertTrue(rendered.contains("Assessment"), "a declining reply should render its Assessment");
    assertTrue(
        rendered.contains("endpoint unreachable at the TCP layer"),
        "the decline summary should render");
    assertFalse(
        rendered.contains("℞ Mitigation"),
        "a declining reply (no prescription) should NOT render Mitigation");
  }

  /** A consultation Document carrying the OSGi-rendered diagnosisAdoc for the checkpoint slug. */
  private static Document consultationDocument(String scenarioId, String diagnosisAdoc) {
    final Consultation payload =
        new Consultation(scenarioId, "", diagnosisAdoc, Map.of(), List.of());
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.CONSULTATION.slug(), CODEC.encode(payload));
  }

  /**
   * Plays the systemd-adapter scenario on failure — the {@code Then} throws, but {@code finished()}
   * still runs in a finally so the failed scenario is flushed into the model and renders (the bug
   * this test guards against). Isolated on the reused scenario so the renderer is tested on its
   * own.
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
