package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.report.asciidoc.AsciiDocReportConfig;
import com.tngtech.jgiven.report.asciidoc.AsciiDocReportGenerator;
import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Renders the shared {@link ReportModel} to the runbook: a directory of AsciiDoc produced by
 * JGiven's {@code AsciiDocReportGenerator}. The generator reads a JSON report directory ({@link
 * ScenarioJsonWriter}) and writes {@code index.asciidoc} plus {@code features/*.asciidoc} and
 * statistics files — it is a multi-file report, not a single document; {@code index.asciidoc} is
 * the entry point.
 *
 * <p>Called from the caller-owned {@code finally} (see {@code BootstrapStage}): the render must
 * survive a CRITICAL scenario that throws, since that is exactly the failure the runbook documents.
 * Rendering never throws back into the pipeline — a runbook that fails to render must not also fail
 * the provisioning.
 */
public final class RunbookRenderer {

  /** Entry point of the rendered report directory. */
  public static final String INDEX_FILE = "index.asciidoc";

  /** Feature the runbook's scenarios are grouped under (JGiven aggregates by class name). */
  private static final String RUNBOOK_FEATURE = "Runbook";

  private final Path outputDir;
  private final Consumer<String> logger;

  public RunbookRenderer(Path outputDir, Consumer<String> logger) {
    this.outputDir = outputDir;
    this.logger = logger;
  }

  /**
   * Render the model to {@link #outputDir}, logging the entry path, after injecting the doctor's
   * diagnosis onto each consulted node. Best-effort: never throws — a runbook that fails to render
   * (or a malformed report) must not also fail the provisioning it documents.
   */
  public void render(ReportModel model, ConsultationLog consultations) {
    try {
      injectDiagnosis(model, consultations);

      final Path jsonDir = outputDir.resolve("json");
      final Path adocDir = outputDir.resolve("adoc");
      recreate(jsonDir);
      recreate(adocDir);

      normalize(model);
      new ScenarioJsonWriter(model).write(jsonDir.resolve("runbook.json").toFile());

      final AsciiDocReportGenerator generator = new AsciiDocReportGenerator();
      final AsciiDocReportConfig config =
          generator.createReportConfig(
              "--sourceDir=" + jsonDir.toAbsolutePath(), "--targetDir=" + adocDir.toAbsolutePath());
      generator.setConfig(config);
      generator.loadReportModel();
      generator.generate();

      log("runbook rendered → " + adocDir.resolve(INDEX_FILE).toAbsolutePath());
    } catch (Exception cause) {
      // A runbook is a narration aid; never let rendering it fail the provisioning.
      log("runbook render skipped (" + cause.getMessage() + ")");
    }
  }

  /**
   * Give the model and its scenarios a feature name. Checkpoints play their scenarios standalone
   * (raw {@code Scenario.create()}, not the JUnit runner), so the class name JGiven groups
   * scenarios by is never set — and the AsciiDoc generator aggregates features by class name, so an
   * unnamed model renders as zero scenarios. Naming it here keeps that rendering detail out of the
   * checkpoints and uniform across all of them.
   */
  private void normalize(ReportModel model) {
    if (model.getName() == null || model.getName().isBlank()) {
      model.setName(RUNBOOK_FEATURE);
    }
    if (model.getClassName() == null || model.getClassName().isBlank()) {
      model.setClassName(RUNBOOK_FEATURE);
    }
    for (ScenarioModel scenario : model.getScenarios()) {
      if (scenario.getClassName() == null || scenario.getClassName().isBlank()) {
        scenario.setClassName(RUNBOOK_FEATURE);
      }
    }
  }

  /**
   * Set each consulted scenario's {@code extendedDescription} to its diagnosis block. The doctor's
   * record and the runbook's {@code ReportModel} are siblings, joined here by the verbatim scenario
   * title — JGiven stores the {@code startScenario(...)} string in {@code getDescription()} (only
   * capitalized for display), so the raw join is {@code getDescription().equals(scenarioTitle())}.
   * The AsciiDoc model visitor emits {@code extendedDescription}, so the block reaches the .adoc.
   */
  private void injectDiagnosis(ReportModel model, ConsultationLog consultations) {
    if (consultations == null || consultations.consultations().isEmpty()) {
      return;
    }
    for (ConsultationReport report : consultations.consultations()) {
      Checkpoint.fromSlug(report.checkpointId())
          .flatMap(checkpoint -> scenarioFor(model, checkpoint))
          .ifPresent(scenario -> scenario.setExtendedDescription(diagnosisBlock(report.plan())));
    }
  }

  private Optional<ScenarioModel> scenarioFor(ReportModel model, Checkpoint checkpoint) {
    return model.getScenarios().stream()
        .filter(scenario -> checkpoint.scenarioTitle().equals(scenario.getDescription()))
        .findFirst();
  }

  /**
   * The Diagnosis (⚕ generalist summary) + Assessment (🔬 specialist reasoning) + Mitigation (℞
   * prescriptions) block, as AsciiDoc text. Each reply's assessment is always rendered; its
   * prescription (mitigation) is rendered only when present.
   */
  private String diagnosisBlock(RemediationPlan plan) {
    final StringBuilder block = new StringBuilder();
    block.append("⚕ Diagnosis: ").append(plan.generalistSummary());
    for (ReferralReply reply : plan.replies()) {
      block
          .append("\n\n🔬 Assessment (")
          .append(reply.assessment().schemaRef().id())
          .append("): ")
          .append(reply.assessment().summary());
      if (reply.hasPrescription()) {
        block
            .append("\n\n℞ Mitigation (")
            .append(reply.prescription().get().programRef().id())
            .append("): ")
            .append(reply.prescription().get().humanHint());
      }
    }
    return block.toString();
  }

  private void recreate(Path dir) throws IOException {
    deleteRecursively(dir);
    Files.createDirectories(dir);
  }

  private void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  private void log(String message) {
    if (logger != null) {
      logger.accept(message);
    }
  }
}
