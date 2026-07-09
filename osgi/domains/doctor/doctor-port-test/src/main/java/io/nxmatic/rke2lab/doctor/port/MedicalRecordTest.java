package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.ChiefComplaint;
import io.nxmatic.rke2lab.doctor.records.Comorbidity;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.records.SymptomHistory;
import io.nxmatic.rke2lab.doctor.records.TreatmentEfficacy;
import io.nxmatic.rke2lab.doctor.records.Visit;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import io.nxmatic.rke2lab.seed.broker.port.Patient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MedicalRecordTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  private static ConsultationReport report(
      String checkpointId, Symptom symptom, RemediationProgramRef... programs) {
    final List<ReferralReply> replies =
        List.of(programs).stream()
            .map(program -> new Prescription(program, Map.of(), "hint:" + program.id()))
            .map(ReferralReplies::treating)
            .toList();
    final RemediationPlan plan = new RemediationPlan(symptom, replies, "summary:" + symptom.id());
    return new ConsultationReport(checkpointId, List.of(), plan);
  }

  private static Visit visit(int version, ConsultationReport... reports) {
    return new Visit(version, Instant.ofEpochSecond(version), List.of(reports), List.of());
  }

  @Test
  void chiefComplaint_returnsLatestVisitReports() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(1, report("c1", Symptom.TIMEOUT)),
                visit(2, report("c2", Symptom.CONNECTION_REFUSED))));

    ChiefComplaint complaint = record.chiefComplaint();

    assertFalse(complaint.isEmpty());
    assertEquals(1, complaint.reports().size());
    assertEquals(Symptom.CONNECTION_REFUSED, complaint.reports().get(0).symptom());
  }

  @Test
  void chiefComplaint_isRobustToInputOrder() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(2, report("c2", Symptom.CONNECTION_REFUSED)),
                visit(1, report("c1", Symptom.TIMEOUT)),
                visit(3, report("c3", Symptom.API_NOT_READY))));

    ChiefComplaint complaint = record.chiefComplaint();

    assertEquals(Symptom.API_NOT_READY, complaint.reports().get(0).symptom());
  }

  @Test
  void chiefComplaint_noVisits_isEmpty() {
    MedicalRecord record = new MedicalRecord(PATIENT, List.of());

    assertTrue(record.chiefComplaint().isEmpty());
  }

  @Test
  void historyOf_chronicSymptom_acrossTwoVisits() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(1, report("alpha", Symptom.CONNECTION_REFUSED)),
                visit(2, report("beta", Symptom.TIMEOUT)),
                visit(3, report("gamma", Symptom.CONNECTION_REFUSED))));

    SymptomHistory history = record.historyOf(Symptom.CONNECTION_REFUSED);

    assertEquals(2, history.count());
    assertTrue(history.isChronic());
    assertEquals(1, history.occurrences().get(0).version());
    assertEquals("alpha", history.occurrences().get(0).checkpointId());
    assertEquals(3, history.occurrences().get(1).version());
    assertEquals("gamma", history.occurrences().get(1).checkpointId());
  }

  @Test
  void historyOf_neverRaised_isEmpty() {
    MedicalRecord record =
        new MedicalRecord(PATIENT, List.of(visit(1, report("alpha", Symptom.TIMEOUT))));

    SymptomHistory history = record.historyOf(Symptom.KUBECONFIG_MISSING);

    assertEquals(0, history.count());
    assertFalse(history.isChronic());
  }

  @Test
  void historyOf_picksFirstReportCheckpointForSymptom() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(
                    1,
                    report("first-timeout", Symptom.TIMEOUT),
                    report("second-timeout", Symptom.TIMEOUT))));

    SymptomHistory history = record.historyOf(Symptom.TIMEOUT);

    assertEquals(1, history.count());
    assertEquals("first-timeout", history.occurrences().get(0).checkpointId());
  }

  @Test
  void efficacyOf_recurredAtNextVisit_neverWorked() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(
                    3,
                    report("c3", Symptom.CONNECTION_REFUSED, RemediationProgramRef.RESTART_UNIT)),
                visit(4, report("c4", Symptom.CONNECTION_REFUSED))));

    TreatmentEfficacy efficacy =
        record.efficacyOf(Symptom.CONNECTION_REFUSED, InterventionLedger.empty());

    assertEquals(1, efficacy.attempts().size());
    TreatmentEfficacy.Attempt attempt = efficacy.attempts().get(0);
    assertEquals(3, attempt.version());
    assertEquals(RemediationProgramRef.RESTART_UNIT.id(), attempt.programRef());
    assertTrue(attempt.recurred());
    assertFalse(efficacy.everWorked());
  }

  @Test
  void efficacyOf_noRecurrenceAtNextVisit_everWorked() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(
                    3,
                    report("c3", Symptom.CONNECTION_REFUSED, RemediationProgramRef.RESTART_UNIT)),
                visit(4, report("c4", Symptom.TIMEOUT))));

    TreatmentEfficacy efficacy =
        record.efficacyOf(Symptom.CONNECTION_REFUSED, InterventionLedger.empty());

    assertEquals(1, efficacy.attempts().size());
    TreatmentEfficacy.Attempt attempt = efficacy.attempts().get(0);
    assertFalse(attempt.recurred());
    assertTrue(efficacy.everWorked());
  }

  @Test
  void efficacyOf_lastVisitHasNoFollowingVisit_noAttempt() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(1, report("c1", Symptom.TIMEOUT)),
                visit(
                    2,
                    report("c2", Symptom.CONNECTION_REFUSED, RemediationProgramRef.RESTART_UNIT))));

    TreatmentEfficacy efficacy =
        record.efficacyOf(Symptom.CONNECTION_REFUSED, InterventionLedger.empty());

    assertTrue(efficacy.attempts().isEmpty());
  }

  @Test
  void efficacyOf_raisedButNoPrescription_noAttempt() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(1, report("c1", Symptom.CONNECTION_REFUSED)),
                visit(2, report("c2", Symptom.CONNECTION_REFUSED))));

    TreatmentEfficacy efficacy =
        record.efficacyOf(Symptom.CONNECTION_REFUSED, InterventionLedger.empty());

    assertTrue(efficacy.attempts().isEmpty());
  }

  @Test
  void efficacyOf_isPerSymptom_anotherSymptomsPrescriptionIsNotCredited() {
    // v1 raises CONNECTION_REFUSED (untreated) AND TIMEOUT (treated). The TIMEOUT treatment must
    // NOT be credited as a CONNECTION_REFUSED attempt — efficacy is per-symptom, not per-visit.
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(
                    1,
                    report("c1", Symptom.CONNECTION_REFUSED),
                    report("c2", Symptom.TIMEOUT, RemediationProgramRef.RESTART_UNIT)),
                visit(2, report("c3", Symptom.CONNECTION_REFUSED))));

    assertTrue(
        record
            .efficacyOf(Symptom.CONNECTION_REFUSED, InterventionLedger.empty())
            .attempts()
            .isEmpty(),
        "untreated symptom must not borrow another symptom's prescription");

    TreatmentEfficacy timeout = record.efficacyOf(Symptom.TIMEOUT, InterventionLedger.empty());
    assertEquals(1, timeout.attempts().size());
    assertEquals(RemediationProgramRef.RESTART_UNIT.id(), timeout.attempts().get(0).programRef());
  }

  @Test
  void comorbiditiesWith_listsOtherCooccurringSymptoms() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(1, report("c1", Symptom.CONNECTION_REFUSED), report("c2", Symptom.TIMEOUT))));

    Comorbidity comorbidity = record.comorbiditiesWith(Symptom.CONNECTION_REFUSED);

    assertEquals(List.of(Symptom.TIMEOUT), comorbidity.cooccurring());
  }

  @Test
  void comorbiditiesWith_isDistinctAcrossVisits() {
    MedicalRecord record =
        new MedicalRecord(
            PATIENT,
            List.of(
                visit(1, report("c1", Symptom.CONNECTION_REFUSED), report("c2", Symptom.TIMEOUT)),
                visit(
                    2,
                    report("c3", Symptom.CONNECTION_REFUSED),
                    report("c4", Symptom.TIMEOUT),
                    report("c5", Symptom.API_NOT_READY))));

    Comorbidity comorbidity = record.comorbiditiesWith(Symptom.CONNECTION_REFUSED);

    assertEquals(List.of(Symptom.TIMEOUT, Symptom.API_NOT_READY), comorbidity.cooccurring());
  }

  @Test
  void nullVisits_normalizedToEmpty() {
    MedicalRecord record = new MedicalRecord(PATIENT, null);

    assertTrue(record.visits().isEmpty());
    assertTrue(record.chiefComplaint().isEmpty());
  }

  @Test
  void visits_sortedByVersionAscending() {
    MedicalRecord record = new MedicalRecord(PATIENT, List.of(visit(3), visit(1), visit(2)));

    assertEquals(List.of(1, 2, 3), record.visits().stream().map(Visit::version).toList());
  }
}
