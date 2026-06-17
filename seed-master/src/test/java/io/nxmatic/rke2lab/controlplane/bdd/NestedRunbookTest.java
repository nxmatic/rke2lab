package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.pulumi.deployment.Deployment;
import com.pulumi.deployment.DeploymentInstance;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepStatus;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.controlplane.pipeline.stages.ClusterReadinessStage;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.VerificationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * The cluster-readiness checkpoint plays the systemd-adapter scenario nested (the follow-the-chain
 * dependency edge) and renders as a two-tier runbook. These tests drive the REAL {@link
 * ClusterReadinessStage#launch()} with an injected probe — the same code production runs — so the
 * scenario script lives in exactly one place. An ordered fake incident on one phase produces a
 * targeted runbook, and the stage consults the doctor on the failing phase's symptom.
 */
class NestedRunbookTest {

  private static final Map<String, Object> REACHABLE_SYSTEMD_ADAPTER =
      Map.of("status", "ok", "summary", "dbusEndpoint reachable");

  private static final Patient TEST_PATIENT = new Patient("organization", "rke2lab", "test");

  /** These tests exercise routing/rendering, not history, so the held record is always empty. */
  private static final MedicalRecordRegistry EMPTY_RECORDS = p -> new MedicalRecord(p, List.of());

  @Test
  void cluster_readiness_renders_with_the_systemd_adapter_dependency_nested(@TempDir Path out) {
    final ReportModel runbook = new ReportModel();
    final VerificationResult result =
        play(runbook, FakeClusterReadinessProbes.allPhasesReady(), false, readyGeneralist());

    assertEquals(1, runbook.getScenarios().size());
    assertEquals(ExecutionStatus.SUCCESS, runbook.getScenarios().get(0).getExecutionStatus());
    assertEquals("Ready", result.bootstrapStatus(), "all phases ok projects to a ready result");

    new RunbookRenderer(out, message -> {}).render(runbook, new ConsultationLog());
    final String report = readAll(out.resolve("adoc"));

    // The cluster checkpoint and its nested systemd-adapter dependency both appear — the two-tier
    // DAG the operator walks from a failure up to its root cause.
    assertTrue(report.contains("Cluster becomes ready"), "cluster scenario should render");
    assertTrue(
        report.toLowerCase().contains("systemd adapter dependency"),
        "the nested systemd-adapter dependency step should render under the cluster scenario");

    // Each readiness phase is its own fluent step — the operator reads which phase passed, not one
    // opaque line. The narration is the step's clause (humanized method name), not the enum's short
    // tag: "the kubeconfig is published" etc.
    assertTrue(report.contains("the kubeconfig is published"), "kubeconfig phase should render");
    assertTrue(report.contains("the api is ready"), "api phase should render");
    assertTrue(
        report.contains("the required controllers are effective"),
        "controllers phase should render");
  }

  @Test
  void cluster_readiness_renders_its_shell_in_preview_dry_run(@TempDir Path out) {
    // Preview = pulumiMode && Deployment.isDryRun(). Mock the static Pulumi seam so the stage takes
    // its preview path: it sets JGiven dry-run (step bodies skipped, no live infra), still plays +
    // finishes the scenario so its shell renders, and sinks a deferred result. This is the fix that
    // makes the cluster checkpoint appear in the runbook on `pulumi preview`.
    final ReportModel runbook = new ReportModel();
    final DeploymentInstance dryRunDeployment = mock(DeploymentInstance.class);
    when(dryRunDeployment.isDryRun()).thenReturn(true);

    final VerificationResult result;
    try (MockedStatic<Deployment> deployment = mockStatic(Deployment.class)) {
      deployment.when(Deployment::getInstance).thenReturn(dryRunDeployment);
      result = play(runbook, FakeClusterReadinessProbes.allPhasesReady(), true, readyGeneralist());
    }

    assertEquals(1, runbook.getScenarios().size());
    assertFalse(result.handoffReady(), "a preview defers the live checks — no handoff");

    new RunbookRenderer(out, message -> {}).render(runbook, new ConsultationLog());
    final String report = readAll(out.resolve("adoc"));
    assertTrue(
        report.contains("Cluster becomes ready"),
        "the cluster scenario shell should render in preview dry-run");
  }

  @Test
  void ordered_fake_incident_on_a_nested_phase_yields_a_targeted_runbook_and_diagnosis(
      @TempDir Path out) {
    // Order an incident at the api-ready phase; the dependency and the kubeconfig phase pass.
    final ClusterReadinessProbe simulated =
        SimulatedClusterReadinessProbe.failingAt(ClusterReadinessPhase.API_READY, Symptom.TIMEOUT);

    final ReportModel model = new ReportModel();
    final ConsultationLog consultations = new ConsultationLog();
    final VerificationResult result =
        play(model, consultations, simulated, false, networkGeneralist());

    // The targeted incident makes the cluster scenario FAIL — a targeted runbook — and the failed
    // projection holds the handoff (the output contract the output layer + Stage B gate consume).
    assertEquals(ExecutionStatus.FAILED, model.getScenarios().get(0).getExecutionStatus());
    assertEquals("Failed", result.bootstrapStatus());
    assertFalse(result.handoffReady());

    // Fail-fast is the fluent chain's own semantics: the failing step throws, so JGiven skips the
    // bodies of the downstream chained steps and marks them SKIPPED. The runbook still SHOWS every
    // phase — the operator sees the one that broke and the ones not reached. Per-step statuses are
    // the rigorous proof the downstream phase body never ran.
    final Map<String, StepStatus> stepStatuses = phaseStepStatuses(model);
    assertEquals(
        StepStatus.PASSED,
        stepStatuses.get("the kubeconfig is published"),
        "the phase upstream of the break ran and passed");
    assertEquals(
        StepStatus.FAILED,
        stepStatuses.get("the api is ready"),
        "the api phase is where the chain broke");
    assertEquals(
        StepStatus.SKIPPED,
        stepStatuses.get("the required controllers are effective"),
        "the phase downstream of the break is skipped — body never played (fail-fast)");

    // The stage itself consults the doctor on the failing phase's symptom (TIMEOUT routes to the
    // network specialist) — proven by the consultation it recorded into the shared log, not by
    // calling Generalist apart. The diagnosis is no longer scraped from the Pulumi log: it reads
    // from the ConsultationLog (and renders into the runbook), the authoritative surface.
    assertEquals(1, consultations.consultations().size(), "the stage should consult the doctor");
    assertEquals(
        RemediationProgramRef.CHECK_CONNECTIVITY,
        consultations
            .consultations()
            .get(0)
            .plan()
            .primaryPrescription()
            .orElseThrow()
            .programRef(),
        "the network specialist's prescription is kept on the consultation");

    new RunbookRenderer(out, message -> {}).render(model, new ConsultationLog());
    final String report = readAll(out.resolve("adoc"));
    assertTrue(report.contains("Cluster becomes ready"));
    assertFalse(
        report.contains("Diagnosis"), "node-level Diagnosis section is Increment C+ (deferred)");
  }

  @Test
  void a_failing_consultation_keeps_its_plan_in_the_shared_log() {
    // The doctor's plan must no longer be computed-logged-then-dropped: a failing checkpoint
    // records
    // a ConsultationReport (the raised observations + the plan) into the shared, caller-owned log —
    // the
    // prerequisite for the medical record (layer 3). Pulumi outputs are untouched (the
    // byte-identical
    // Stage-B contract holds); this only adds an in-memory accumulation.
    final ClusterReadinessProbe simulated =
        SimulatedClusterReadinessProbe.failingAt(ClusterReadinessPhase.API_READY, Symptom.TIMEOUT);
    final ConsultationLog consultations = new ConsultationLog();

    play(new ReportModel(), consultations, simulated, false, networkGeneralist());

    assertEquals(
        1,
        consultations.consultations().size(),
        "the failing checkpoint should record one consultation");
    final ConsultationReport consultation = consultations.consultations().get(0);
    assertEquals(
        Symptom.TIMEOUT, consultation.symptom(), "the consultation names the raised symptom");
    assertTrue(
        consultation.plan().hasPrescriptions(),
        "the network specialist's prescription is kept on the report, not dropped");
    assertEquals(
        RemediationProgramRef.CHECK_CONNECTIVITY,
        consultation.plan().primaryPrescription().orElseThrow().programRef());
  }

  @Test
  void a_healthy_consultation_records_nothing() {
    // Reactive-consultation model: no symptom raised → no doctor consultation → no report.
    final ConsultationLog consultations = new ConsultationLog();

    play(
        new ReportModel(),
        consultations,
        FakeClusterReadinessProbes.allPhasesReady(),
        false,
        readyGeneralist());

    assertTrue(
        consultations.consultations().isEmpty(),
        "a checkpoint that raises no symptom records no consultation");
  }

  /** Play the real stage with no log capture. */
  private static VerificationResult play(
      ReportModel runbook, ClusterReadinessProbe probe, boolean pulumiMode, Generalist generalist) {
    return play(runbook, probe, pulumiMode, generalist, message -> {});
  }

  private static VerificationResult play(
      ReportModel runbook,
      ClusterReadinessProbe probe,
      boolean pulumiMode,
      Generalist generalist,
      java.util.function.Consumer<String> logger) {
    return play(runbook, new ConsultationLog(), probe, pulumiMode, generalist, logger);
  }

  private static VerificationResult play(
      ReportModel runbook,
      ConsultationLog consultations,
      ClusterReadinessProbe probe,
      boolean pulumiMode,
      Generalist generalist) {
    return play(runbook, consultations, probe, pulumiMode, generalist, message -> {});
  }

  /**
   * Drive the production {@link ClusterReadinessStage#launch()} with an injected probe and capture
   * its {@link VerificationResult}. This is the single owner of the scenario script — the test
   * varies only the probe (fake/simulated), the runbook model, the consultation log, and the log
   * sink.
   */
  private static VerificationResult play(
      ReportModel runbook,
      ConsultationLog consultations,
      ClusterReadinessProbe probe,
      boolean pulumiMode,
      Generalist generalist,
      java.util.function.Consumer<String> logger) {
    final VerificationResult[] holder = new VerificationResult[1];
    new ClusterReadinessStage(
            config(),
            policy(),
            true,
            pulumiMode,
            logger,
            runbook,
            consultations,
            generalist,
            probe,
            REACHABLE_SYSTEMD_ADAPTER,
            result -> holder[0] = result)
        .launch();
    return holder[0];
  }

  private static Generalist generalistWith(List<Specialist> specialists) {
    final GrantPolicy policy =
        GrantPolicy.empty().withSelfGrant(Generalist.GENERALIST_ID, TEST_PATIENT);
    final ClinicalAccess access =
        new ClinicalAccess(
            Generalist.GENERALIST_ID, TEST_PATIENT, policy, EMPTY_RECORDS, msg -> {});
    return Generalist.builder().specialists(specialists).access(access).build();
  }

  /** Map each top-level When step's rendered name to its step status. */
  private static Map<String, StepStatus> phaseStepStatuses(ReportModel model) {
    final Map<String, StepStatus> statuses = new LinkedHashMap<>();
    model
        .getScenarios()
        .get(0)
        .getScenarioCases()
        .get(0)
        .getSteps()
        .forEach(step -> statuses.put(step.getName(), step.getStatus()));
    return statuses;
  }

  private static Generalist readyGeneralist() {
    return generalistWith(List.of(new DbusTcpSpecialist(config())));
  }

  private static Generalist networkGeneralist() {
    return generalistWith(List.of(new DbusTcpSpecialist(config()), new FakeNetworkSpecialist()));
  }

  /** A stand-in network specialist so a TIMEOUT (routed to NETWORK) yields a prescription. */
  private static final class FakeNetworkSpecialist implements Specialist {
    @Override
    public Specialty domain() {
      return Specialty.NETWORK;
    }

    @Override
    public ReferralReply diagnose(Referral referral) {
      final Assessment assessment =
          Assessment.of(
              SchemaRef.of("network/check-connectivity/v1"),
              Map.of("symptom", referral.symptom().id()),
              "the API endpoint may be unreachable — verify network connectivity first");
      final Prescription prescription =
          Prescription.of(
              RemediationProgramRef.CHECK_CONNECTIVITY,
              Map.of("symptom", referral.symptom().id()),
              "check connectivity to the API endpoint");
      return ReferralReply.prescribing(referral, assessment, prescription);
    }
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

  private static ControlplanePolicy policy() {
    return OperatorConfiguration.mandatory().asPolicy();
  }
}
