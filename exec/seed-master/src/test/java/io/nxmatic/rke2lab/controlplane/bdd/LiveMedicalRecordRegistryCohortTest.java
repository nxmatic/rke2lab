package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.port.ConsultationReport;
import io.nxmatic.rke2lab.doctor.port.InterventionLedger;
import io.nxmatic.rke2lab.doctor.port.MedicalRecord;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.port.Observation;
import io.nxmatic.rke2lab.doctor.port.Patient;
import io.nxmatic.rke2lab.doctor.port.Prescription;
import io.nxmatic.rke2lab.doctor.port.ReferralReply;
import io.nxmatic.rke2lab.doctor.port.RemediationPlan;
import io.nxmatic.rke2lab.doctor.port.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.port.Symptom;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import io.nxmatic.rke2lab.pulumi.automation.testkit.StackHistoryFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry's cross-patient enumerate-and-fold: prove the doctor can REACH and FOLD a SECOND
 * patient's record. Two patients share one file backend — {@code dev} (the patient in the room,
 * freshly raising CONNECTION_REFUSED) and {@code peer} (a prior CONNECTION_REFUSED that
 * RESTART_UNIT resolved). {@link MedicalRecordRegistry#cohortFor} enumerates both and folds; we
 * assert the cross-patient finding the doctor could surface ("seen on N patients; M prior
 * RESTART_UNIT resolved it"). The registry itself is ungated by design — the {@code (ClinicianId,
 * Patient)} grant filter is applied one layer up by {@link ClinicalAccess}, not here.
 */
class LiveMedicalRecordRegistryCohortTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PROJECT = "rke2lab";

  @TempDir Path backend;

  private static String latestWith(ConsultationReport report) throws Exception {
    final Map<String, Object> stackResource =
        Map.of("type", "pulumi:pulumi:Stack", "outputs", Map.of());
    final Map<String, Object> checkpointResource =
        Map.of(
            "type",
            "rke2lab:controlplane:Checkpoint",
            "outputs",
            Map.of(ConsultationReport.OUTPUT_KEY, report.toOutputMap()));
    return JSON.writeValueAsString(Map.of("resources", List.of(stackResource, checkpointResource)));
  }

  private static ConsultationReport report(Symptom symptom, RemediationProgramRef program) {
    final Observation observation = Observation.failed(symptom, "seeded " + symptom.id(), Map.of());
    final List<ReferralReply> replies =
        program == null
            ? List.of()
            : List.of(ReferralReplies.treating(Prescription.of(program, Map.of(), "seeded hint")));
    return new ConsultationReport(
        "seeded-systemd-adapter",
        List.of(observation),
        new RemediationPlan(symptom, replies, "seeded"));
  }

  @Test
  void foldsACrossPatientFindingFromASiblingsRecord() throws Exception {
    // peer: v1 CONNECTION_REFUSED + RESTART_UNIT, v2 the symptom is GONE → the treatment WORKED.
    StackHistoryFixture.at(backend, PROJECT, "peer")
        .updateWithLatest(
            1_780_000_001L,
            latestWith(report(Symptom.CONNECTION_REFUSED, RemediationProgramRef.RESTART_UNIT)))
        .updateWithLatest(1_780_000_002L, latestWith(report(Symptom.TIMEOUT, null)));

    // dev: the patient in the room — one visit freshly raising CONNECTION_REFUSED, untreated.
    StackHistoryFixture.at(backend, PROJECT, "dev")
        .updateWithLatest(1_780_000_010L, latestWith(report(Symptom.CONNECTION_REFUSED, null)));

    final Patient dev = new Patient("organization", PROJECT, "dev");
    final MedicalRecordRegistry registry = new LiveMedicalRecordRegistry(backend, message -> {});
    final List<MedicalRecord> cohort = registry.cohortFor(dev);

    // The cohort is both patients, current (dev) first.
    assertEquals(2, cohort.size());
    assertEquals("dev", cohort.get(0).patient().stack());

    // The fold: how many patients in the cohort have ever raised CONNECTION_REFUSED, and how many
    // prior treatments for it actually resolved (efficacyOf, now read ACROSS patients). This is the
    // cross-patient primitive the cohort read provides.
    final long patientsWithSymptom =
        cohort.stream().filter(r -> r.historyOf(Symptom.CONNECTION_REFUSED).count() > 0).count();
    final long priorTreatmentsThatWorked =
        cohort.stream()
            .filter(
                r ->
                    r.efficacyOf(Symptom.CONNECTION_REFUSED, InterventionLedger.empty())
                        .everWorked())
            .count();

    assertEquals(2, patientsWithSymptom); // dev (now) + peer (history)
    assertEquals(1, priorTreatmentsThatWorked); // peer's RESTART_UNIT held

    // dev alone knows nothing about a working treatment; the BENEFIT comes only from the sibling.
    assertFalse(
        registry
            .recordFor(dev)
            .efficacyOf(Symptom.CONNECTION_REFUSED, InterventionLedger.empty())
            .everWorked());
    assertTrue(
        registry
            .recordFor(new Patient("organization", PROJECT, "peer"))
            .efficacyOf(Symptom.CONNECTION_REFUSED, InterventionLedger.empty())
            .everWorked());
  }
}
