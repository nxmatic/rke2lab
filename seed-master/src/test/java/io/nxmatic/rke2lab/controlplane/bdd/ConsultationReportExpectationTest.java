package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
}
