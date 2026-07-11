package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.VerificationResult;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.doctor.ExactRosterDoctor;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import io.nxmatic.rke2lab.pulumi.edge.RunMode;
import io.nxmatic.rke2lab.seed.broker.codec.DocumentCodec;
import io.nxmatic.rke2lab.seed.broker.port.Consultation;
import io.nxmatic.rke2lab.seed.broker.port.Document;
import io.nxmatic.rke2lab.seed.broker.port.Patient;
import io.nxmatic.rke2lab.seed.broker.port.SymptomKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cluster-readiness failure path — a checkpoint that FAILS on an ordered incident, consults the
 * doctor on the raised symptom, and records the diagnosis into the shared log. This is the ONLY
 * coverage of that path: {@code SystemdAdapterVerdictTest} drives the composite with a failing
 * SYSTEMD probe, which aborts at the systemd phase before cluster-readiness is reached.
 *
 * <p>DISABLED — the harness below (a lightweight {@code Scenario.create} + direct step calls) does
 * NOT reproduce jGiven's real interception: the readiness phases run below the step-depth cap AND
 * the {@code capturing} probe set on the inner {@code Given} never flows to the inner {@code When}
 * (that cross-stage state moves only through genuine {@code enterStage}/{@code leaveStage}
 * transitions). So the failure-path assertions cannot pass here. The assertions ARE the spec; the
 * harness must be REDONE on the real launcher + Felix with a {@code cluster-core-fake} fragment
 * (the {@code SystemdAdapterStageTest} pattern: {@code (variant=fake)} selector resolves a
 * registry-published fake), delivered by the "uniform per-domain OSGi fakes" chantier. Re-enable
 * there. See the memory entry for that chantier.
 */
@Disabled(
    "Re-enable in the per-domain OSGi fakes chantier: the failure-path harness must be redone on the"
        + " real launcher + Felix with a cluster-core-fake fragment (Scenario.create cannot"
        + " reproduce jGiven's cross-stage interception). The assertions here are the spec.")
class NestedRunbookTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DocumentCodec CODEC = new DocumentCodec();
  private static final Patient TEST_PATIENT = new Patient("organization", "rke2lab", "test");

  @Test
  void cluster_readiness_renders_its_checkpoint_when_all_pass(@TempDir Path out) {
    final ReportModel runbook = new ReportModel();
    final VerificationResult result =
        play(
            runbook,
            new ConsultationLog(),
            FakeClusterReadinessProbes.allPhasesReady(),
            readyGeneralist());

    assertEquals(1, runbook.getScenarios().size());
    assertEquals(ExecutionStatus.SUCCESS, runbook.getScenarios().get(0).getExecutionStatus());
    assertEquals("Ready", result.bootstrapStatus(), "all phases ok projects to a ready result");
    assertTrue(result.handoffReady(), "a ready cluster hands off");

    new RunbookRenderer(out, message -> {}).render(runbook, new ConsultationLog());
    final String report = readAll(out.resolve("adoc"));

    // The checkpoint renders as one step — the readiness phases are encapsulated inside it (they
    // play against the injected probe, below jGiven's step-depth cap), so the runbook shows the
    // cluster checkpoint, not per-phase lines. The FAILED case surfaces which phase broke via the
    // doctor consultation, not a per-phase step (see the incident test).
    assertTrue(report.contains("cluster becomes ready"), "the cluster checkpoint should render");
  }

  @Test
  void ordered_fake_incident_yields_a_failed_checkpoint_and_a_diagnosis(@TempDir Path out) {
    // Order an incident at the api-ready phase; the checkpoint fails on it.
    final ClusterReadinessProbe simulated =
        SimulatedClusterReadinessProbe.failingAt(
            ClusterReadinessPhase.API_READY, SymptomKind.TIMEOUT);
    final ReportModel model = new ReportModel();
    final ConsultationLog consultations = new ConsultationLog();

    final VerificationResult result = play(model, consultations, simulated, networkGeneralist());

    // The targeted incident makes the checkpoint FAIL — a targeted runbook — and the failed
    // projection holds the handoff contract the output layer + gate consume.
    assertEquals(ExecutionStatus.FAILED, model.getScenarios().get(0).getExecutionStatus());
    assertEquals("Failed", result.bootstrapStatus());
    assertFalse(result.handoffReady());

    // The stage itself consults the doctor on the failing phase's symptom (TIMEOUT routes to the
    // network specialist) — proven by the consultation it recorded into the shared log.
    assertEquals(1, consultations.consultations().size(), "the stage should consult the doctor");
    final JsonNode plan = consultationReport(consultations.consultations().get(0)).path("plan");
    assertEquals(
        "check-connectivity",
        plan.path("replies").path(0).path("prescription").path("programRef").asText(),
        "the network specialist's prescription is kept on the consultation");

    new RunbookRenderer(out, message -> {}).render(model, new ConsultationLog());
    final String report = readAll(out.resolve("adoc"));
    assertTrue(report.contains("cluster becomes ready"));
  }

  @Test
  void a_failing_consultation_keeps_its_plan_in_the_shared_log() {
    // The doctor's plan must not be computed-logged-then-dropped: a failing checkpoint records a
    // ConsultationReport (the raised observations + the plan) into the shared, caller-owned log.
    final ClusterReadinessProbe simulated =
        SimulatedClusterReadinessProbe.failingAt(
            ClusterReadinessPhase.API_READY, SymptomKind.TIMEOUT);
    final ConsultationLog consultations = new ConsultationLog();

    play(new ReportModel(), consultations, simulated, networkGeneralist());

    assertEquals(
        1,
        consultations.consultations().size(),
        "the failing checkpoint should record one consultation");
    final JsonNode plan = consultationReport(consultations.consultations().get(0)).path("plan");
    // The plan's symptom is the kebab wire id; the enum itself is doctor.records, off the host
    // seam.
    assertEquals(
        "timeout", plan.path("symptom").asText(), "the consultation names the raised symptom");
    assertEquals(
        "check-connectivity",
        plan.path("replies").path(0).path("prescription").path("programRef").asText());
  }

  @Test
  void a_healthy_consultation_records_nothing() {
    // Reactive-consultation model: no symptom raised → no doctor consultation → no report.
    final ConsultationLog consultations = new ConsultationLog();

    play(
        new ReportModel(),
        consultations,
        FakeClusterReadinessProbes.allPhasesReady(),
        readyGeneralist());

    assertTrue(
        consultations.consultations().isEmpty(),
        "a checkpoint that raises no symptom records no consultation");
  }

  /**
   * Play {@link ClusterReadinessStage} in isolation on a lightweight {@code Scenario.create} — no
   * launcher, no Felix. The stage's {@code @ExpectedScenarioState} context is seeded through the
   * SAME {@link StageContext} carrier {@code HostSeeder} uses in production ({@code
   * executor.readScenarioState}); the doctor is served from a {@link StubConnection} (the stage
   * resolves it via {@code awaitService}). Returns the {@link VerificationResult} the stage
   * provided.
   */
  private static VerificationResult play(
      ReportModel runbook,
      ConsultationLog consultations,
      ClusterReadinessProbe probe,
      ConsultingService doctor) {
    final Scenario<Given, ClusterReadinessStage, Then> scenario =
        Scenario.create(Given.class, ClusterReadinessStage.class, Then.class);
    scenario.setModel(runbook);

    final StageContext carrier = new StageContext();
    carrier.hostFacts = facts(consultations);
    carrier.connection = StubConnection.serving(Map.of(ConsultingService.class, doctor));
    carrier.clusterProbe = Optional.of(probe);
    scenario.getExecutor().readScenarioState(carrier);
    // The upstream incus outcome is produced by IncusStage in the full seed; isolated here it is
    // legitimately empty (its only use is stamping the consult checkpoint's recordedAt). Seeded as
    // a separate DAG value, not through the host carrier (the host never produces it).
    scenario.getExecutor().readScenarioState(new IncusOutcome());

    // startScenario triggers the lazy init that creates the stages, so getWhenStage is valid only
    // after it. The carrier state seeded above persists in the executor's injector and is applied
    // to
    // the stage when its step runs.
    scenario.startScenario("cluster becomes ready");
    final ClusterReadinessStage stage = scenario.getWhenStage();
    try {
      try {
        stage.the_cluster_is_verified_ready();
      } finally {
        scenario.finished();
      }
    } catch (Throwable expected) {
      // A failing readiness phase throws (fail-fast); finished() already flushed the model.
    }
    return stage.verification;
  }

  /** Empty triptych slots — {@link ClusterReadinessStage} is the When under test. */
  public static class Given extends Stage<Given> {}

  public static class Then extends Stage<Then> {}

  /**
   * The upstream incus outcome, seeded into the DAG for the isolated cluster-readiness play (in the
   * full seed it comes from {@code IncusStage}). Empty here — the readiness stage only reads it to
   * stamp the consult checkpoint's timestamp. Mirrors the stage's {@code @ExpectedScenarioState}
   * field by type ({@code Optional}).
   */
  static final class IncusOutcome {
    @ProvidedScenarioState Optional<BootstrapResult> bootstrap = Optional.empty();
  }

  private static HostFacts facts(ConsultationLog consultations) {
    final var cfg = OperatorConfiguration.mandatory();
    return new HostFacts(
        cfg.asBootstrapConfig(),
        cfg.asPolicy(),
        BootstrapOptions.from(cfg.asDto()),
        LiveGate.forRun(RunMode.STANDALONE),
        RunMode.STANDALONE.materialises(),
        new BboxReconciliationOrchestrator(false),
        new ResourceManager(),
        new OutputBuilder(),
        message -> {},
        OnFailure.noop(),
        consultations);
  }

  private static JsonNode consultationReport(Document consultation) {
    final Consultation decoded = CODEC.decode(consultation, Consultation.class);
    return MAPPER.valueToTree(decoded.consultationReport());
  }

  private static ConsultingService readyGeneralist() {
    return ExactRosterDoctor.readyGeneralist(TEST_PATIENT);
  }

  private static ConsultingService networkGeneralist() {
    return ExactRosterDoctor.networkGeneralist(TEST_PATIENT);
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
}
