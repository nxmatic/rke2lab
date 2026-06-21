package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.Assessment;
import io.nxmatic.rke2lab.doctor.Checkpoint;
import io.nxmatic.rke2lab.doctor.ConsultationReport;
import io.nxmatic.rke2lab.doctor.Expectation;
import io.nxmatic.rke2lab.doctor.Observation;
import io.nxmatic.rke2lab.doctor.Prescription;
import io.nxmatic.rke2lab.doctor.ReferralReply;
import io.nxmatic.rke2lab.doctor.RemediationPlan;
import io.nxmatic.rke2lab.doctor.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.ResolutionPredicate;
import io.nxmatic.rke2lab.doctor.SchemaRef;
import io.nxmatic.rke2lab.doctor.Symptom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * Pins the derivation of {@link Expectation}s from a prescribing consultation: each prescription
 * predicts that its symptom resolves by the next visit. A plan with no prescription predicts
 * nothing.
 */
class ConsultationReportExpectationTest {

  private static final Instant RECORDED_AT = Instant.parse("2026-06-13T10:00:00Z");

  @Test
  void a_prescribing_consultation_predicts_the_symptom_resolves() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
    final Prescription prescription =
        Prescription.of(
            RemediationProgramRef.RESTART_UNIT, Map.of("unit", "systemd-adapter"), "restart it");
    final RemediationPlan plan =
        new RemediationPlan(
            Symptom.CONNECTION_REFUSED,
            List.of(ReferralReplies.treating(prescription)),
            "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(observation), plan);

    final List<Expectation> expectations = report.expectations(RECORDED_AT);

    assertEquals(1, expectations.size());
    final Expectation expectation = expectations.get(0);
    assertEquals(Symptom.CONNECTION_REFUSED, expectation.symptom());
    assertEquals("systemd-adapter/connection-refused", expectation.problem().toRef());
    assertEquals(RemediationProgramRef.RESTART_UNIT, expectation.fromPrescription());
    assertEquals(new ResolutionPredicate(Symptom.CONNECTION_REFUSED), expectation.predicate());
    assertEquals(RECORDED_AT, expectation.recordedAt());
  }

  @Test
  void a_consultation_with_no_prescription_predicts_nothing() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
    final Assessment assessment =
        Assessment.of(
            SchemaRef.of("network/reachability/v1"),
            Map.of(),
            "endpoint unreachable; no remediation");
    final ReferralReply declined =
        ReferralReply.reconstructed(assessment, java.util.Optional.empty());
    final RemediationPlan plan =
        new RemediationPlan(Symptom.CONNECTION_REFUSED, List.of(declined), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(observation), plan);

    assertTrue(report.expectations(RECORDED_AT).isEmpty());
  }

  @Test
  void a_report_with_unknown_checkpoint_id_throws_on_expectations() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
    final Prescription prescription =
        Prescription.of(
            RemediationProgramRef.RESTART_UNIT, Map.of("unit", "systemd-adapter"), "restart it");
    final RemediationPlan plan =
        new RemediationPlan(
            Symptom.CONNECTION_REFUSED,
            List.of(ReferralReplies.treating(prescription)),
            "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport("not-a-checkpoint", List.of(observation), plan);

    assertThrows(NoSuchElementException.class, () -> report.expectations(RECORDED_AT));
  }
}
