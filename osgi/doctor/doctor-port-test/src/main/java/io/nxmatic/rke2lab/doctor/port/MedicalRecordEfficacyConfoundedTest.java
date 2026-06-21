package io.nxmatic.rke2lab.doctor.port;

import static io.nxmatic.rke2lab.doctor.port.Checkpoint.SYSTEMD_ADAPTER;
import static io.nxmatic.rke2lab.doctor.port.Symptom.CONNECTION_REFUSED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MedicalRecordEfficacyConfoundedTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");
  private static final Instant T0 = Instant.parse("2026-06-14T09:00:00Z");
  private static final Instant T_MID = Instant.parse("2026-06-14T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-06-14T11:00:00Z");

  /**
   * A two-visit record: v0 raises CONNECTION_REFUSED and prescribes RESTART_UNIT; v1 is clean (the
   * symptom resolved). The treatment "appears" to have worked — unless something else fixed it.
   */
  private static MedicalRecord resolvedAfterRestart() {
    final Prescription rx = new Prescription(RemediationProgramRef.RESTART_UNIT, Map.of(), "hint");
    final RemediationPlan plan =
        new RemediationPlan(CONNECTION_REFUSED, List.of(ReferralReplies.treating(rx)), "summary");
    final ConsultationReport report = new ConsultationReport("systemd-adapter", List.of(), plan);
    final Visit v0 = new Visit(0, T0, List.of(report), List.of());
    final Visit v1 = new Visit(1, T1, List.of(), List.of());
    return new MedicalRecord(PATIENT, List.of(v0, v1));
  }

  private static Intervention windowIntervention(Provenance provenance) {
    return new Intervention(
        provenance,
        T_MID,
        "out-of-band fix",
        ProblemRef.of(SYSTEMD_ADAPTER, CONNECTION_REFUSED),
        Optional.empty(),
        Map.of());
  }

  @Test
  void emptyLedger_notConfounded_treatmentCredited() {
    final TreatmentEfficacy efficacy =
        resolvedAfterRestart().efficacyOf(CONNECTION_REFUSED, InterventionLedger.empty());
    assertEquals(1, efficacy.attempts().size());
    final TreatmentEfficacy.Attempt attempt = efficacy.attempts().get(0);
    assertFalse(attempt.recurred(), "symptom resolved at next visit");
    assertFalse(attempt.confounded(), "no intervention in the window");
    assertTrue(efficacy.everWorked(), "with no confounder, the resolution is credited");
  }

  @Test
  void operatorManualInWindow_confounded_notCredited() {
    final InterventionLedger ledger =
        new InterventionLedger(List.of(windowIntervention(Provenance.OPERATOR_MANUAL)));
    final TreatmentEfficacy efficacy =
        resolvedAfterRestart().efficacyOf(CONNECTION_REFUSED, ledger);
    final TreatmentEfficacy.Attempt attempt = efficacy.attempts().get(0);
    assertFalse(attempt.recurred());
    assertTrue(attempt.confounded(), "a declared operator fix in the window confounds the credit");
    assertFalse(efficacy.everWorked(), "a confounded attempt is not counted as working");
  }

  @Test
  void externalChangeInWindow_confounded_notCredited() {
    final InterventionLedger ledger =
        new InterventionLedger(List.of(windowIntervention(Provenance.EXTERNAL_CHANGE_DETECTED)));
    final TreatmentEfficacy efficacy =
        resolvedAfterRestart().efficacyOf(CONNECTION_REFUSED, ledger);
    final TreatmentEfficacy.Attempt attempt = efficacy.attempts().get(0);
    assertTrue(
        attempt.confounded(), "an inferred external change in the window confounds the credit");
    assertFalse(efficacy.everWorked());
  }
}
