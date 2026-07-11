package io.nxmatic.rke2lab.doctor.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pins the clinical query-view records reconstructed from a patient's consultation reports. */
class DoctorRecordsTest {

  private static ConsultationReport report(
      String checkpointId, Symptom symptom, RemediationProgramRef... programs) {
    final List<ReferralReply> replies =
        Arrays.stream(programs)
            .map(p -> Prescription.of(p, Map.of(), "hint"))
            .map(ReferralReplies::treating)
            .toList();
    return new ConsultationReport(
        checkpointId,
        List.of(Observation.failed(symptom, "summary", Map.of())),
        new RemediationPlan(symptom, replies, "summary"));
  }

  @Test
  void visit_symptomsRaised_is_the_distinct_symptoms_across_reports() {
    final Visit visit =
        new Visit(
            1,
            Instant.EPOCH,
            List.of(
                report("a", Symptom.CONNECTION_REFUSED, RemediationProgramRef.RESTART_UNIT),
                report("b", Symptom.TIMEOUT),
                report("c", Symptom.CONNECTION_REFUSED)),
            List.of());

    assertEquals(Set.of(Symptom.CONNECTION_REFUSED, Symptom.TIMEOUT), visit.symptomsRaised());
  }

  @Test
  void visit_prescriptions_are_every_programRef_in_order_with_duplicates() {
    final Visit visit =
        new Visit(
            2,
            Instant.EPOCH,
            List.of(
                report(
                    "a",
                    Symptom.CONNECTION_REFUSED,
                    RemediationProgramRef.RESTART_UNIT,
                    RemediationProgramRef.CHECK_CONNECTIVITY),
                report("b", Symptom.TIMEOUT, RemediationProgramRef.RESTART_UNIT)),
            List.of());

    assertEquals(
        List.of(
            RemediationProgramRef.RESTART_UNIT,
            RemediationProgramRef.CHECK_CONNECTIVITY,
            RemediationProgramRef.RESTART_UNIT),
        visit.prescriptions());
  }

  @Test
  void visit_normalizes_null_reports_without_npe() {
    final Visit visit = new Visit(1, Instant.EPOCH, null, null);

    assertTrue(visit.reports().isEmpty());
    assertTrue(visit.symptomsRaised().isEmpty());
    assertTrue(visit.prescriptions().isEmpty());
  }

  @Test
  void chiefComplaint_isEmpty_reflects_its_reports() {
    assertTrue(new ChiefComplaint(List.of()).isEmpty());
    assertTrue(new ChiefComplaint(null).isEmpty());
    assertFalse(new ChiefComplaint(List.of(report("a", Symptom.CONNECTION_REFUSED))).isEmpty());
  }

  @Test
  void symptomHistory_count_and_chronicity() {
    final SymptomHistory acute =
        new SymptomHistory(
            Symptom.CONNECTION_REFUSED, List.of(new SymptomHistory.Occurrence(1, "a")));
    assertEquals(1, acute.count());
    assertFalse(acute.isChronic());

    final SymptomHistory chronic =
        new SymptomHistory(
            Symptom.CONNECTION_REFUSED,
            List.of(new SymptomHistory.Occurrence(1, "a"), new SymptomHistory.Occurrence(2, "a")));
    assertEquals(2, chronic.count());
    assertTrue(chronic.isChronic());
  }

  @Test
  void symptomHistory_normalizes_null_occurrences() {
    assertEquals(0, new SymptomHistory(Symptom.TIMEOUT, null).count());
  }

  @Test
  void treatmentEfficacy_everWorked_when_any_attempt_did_not_recur() {
    final TreatmentEfficacy neverWorked =
        new TreatmentEfficacy(
            Symptom.CONNECTION_REFUSED,
            List.of(new TreatmentEfficacy.Attempt(1, "restart-systemd-unit", true, false)));
    assertFalse(neverWorked.everWorked());

    final TreatmentEfficacy worked =
        new TreatmentEfficacy(
            Symptom.CONNECTION_REFUSED,
            List.of(
                new TreatmentEfficacy.Attempt(1, "restart-systemd-unit", true, false),
                new TreatmentEfficacy.Attempt(2, "restart-systemd-unit", false, false)));
    assertTrue(worked.everWorked());
  }

  @Test
  void treatmentEfficacy_normalizes_null_attempts() {
    assertFalse(new TreatmentEfficacy(Symptom.TIMEOUT, null).everWorked());
  }

  @Test
  void comorbidity_lists_its_cooccurring_symptoms() {
    final Comorbidity comorbidity =
        new Comorbidity(
            Symptom.CONNECTION_REFUSED, List.of(Symptom.TIMEOUT, Symptom.API_NOT_READY));

    assertEquals(List.of(Symptom.TIMEOUT, Symptom.API_NOT_READY), comorbidity.cooccurring());
  }

  @Test
  void comorbidity_normalizes_null() {
    assertTrue(new Comorbidity(Symptom.TIMEOUT, null).cooccurring().isEmpty());
  }
}
