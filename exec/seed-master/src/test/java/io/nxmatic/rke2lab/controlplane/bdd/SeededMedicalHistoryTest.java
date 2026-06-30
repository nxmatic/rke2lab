package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordReader;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.records.SymptomHistory;
import io.nxmatic.rke2lab.doctor.records.TreatmentEfficacy;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import io.nxmatic.rke2lab.pulumi.edge.StackHandle;
import io.nxmatic.rke2lab.pulumi.edge.StackHandleSnapshotSource;
import io.nxmatic.rke2lab.pulumi.edge.testkit.StackHistoryFixture;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seeds a throwaway file backend with a multi-visit clinical history — the longitudinal axis dev's
 * real state cannot exercise (it predates the doctor write-side) and that {@code pulumi import}
 * cannot build (it writes no history entry). Each seeded report is in the real {@link
 * ConsultationReport#toOutputMap()} shape and carries a {@code seeded} tag in its observation
 * details, so the reconstructed record is honestly distinguishable from doctor-produced data.
 * Proves the record reconstructs a recurring symptom (chronic) and a treatment's efficacy across
 * deployments, end to end through {@link StackHandle} + {@link MedicalRecordReader}.
 */
class SeededMedicalHistoryTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SEEDED_TAG = "synthetic — injected for Task 14, not doctor-produced";

  @TempDir Path tempDir;

  /** The {@code {"resources":[...]}} latest-body for a checkpoint carrying one tagged report. */
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

  /** A tagged report for {@code symptom}, optionally prescribing {@code program}. */
  private static ConsultationReport seededReport(Symptom symptom, RemediationProgramRef program) {
    final Observation observation =
        Observation.failed(symptom, "seeded " + symptom.id(), Map.of("seeded", SEEDED_TAG));
    final List<ReferralReply> replies =
        program == null
            ? List.of()
            : List.of(
                ReferralReplies.treating(
                    Prescription.of(program, Map.of("seeded", SEEDED_TAG), "seeded hint")));
    return new ConsultationReport(
        "seeded-systemd-adapter",
        List.of(observation),
        new RemediationPlan(symptom, replies, "seeded generalist summary"));
  }

  @Test
  void reconstructsAChronicSymptomAndTreatmentEfficacyFromSeededHistory() throws Exception {
    // Three deployments: v1 raises CONNECTION_REFUSED + prescribes a restart; v2 it recurs (the
    // treatment did not hold); v3 it is gone. Ascending startTimes => ascending deployment
    // instants.
    final StackHistoryFixture fixture =
        StackHistoryFixture.at(tempDir, "rke2lab", "seeded")
            .updateWithLatest(
                1_780_000_001L,
                latestWith(
                    seededReport(Symptom.CONNECTION_REFUSED, RemediationProgramRef.RESTART_UNIT)))
            .updateWithLatest(
                1_780_000_002L, latestWith(seededReport(Symptom.CONNECTION_REFUSED, null)))
            .updateWithLatest(1_780_000_003L, latestWith(seededReport(Symptom.TIMEOUT, null)));

    final StackHandle handle = StackHandle.forBackend(fixture.backendDir(), "rke2lab", "seeded");
    final MedicalRecord record =
        new MedicalRecordReader(new StackHandleSnapshotSource(handle))
            .read(new Patient("organization", "rke2lab", "seeded"));

    // Three visits, in chronological order.
    assertEquals(3, record.visits().size());

    // The symptom recurred across two visits → chronic.
    final SymptomHistory history = record.historyOf(Symptom.CONNECTION_REFUSED);
    assertEquals(2, history.count());
    assertTrue(history.isChronic());

    // v1 prescribed a restart, v2 still raised the symptom → the treatment did not work.
    final TreatmentEfficacy efficacy =
        record.efficacyOf(Symptom.CONNECTION_REFUSED, InterventionLedger.empty());
    assertEquals(1, efficacy.attempts().size());
    assertEquals(RemediationProgramRef.RESTART_UNIT.id(), efficacy.attempts().get(0).programRef());
    assertTrue(efficacy.attempts().get(0).recurred());
    assertFalse(efficacy.everWorked());

    // The seeded tag survived reconstruction (additive key-bag) — the data is honestly labelled.
    final ConsultationReport firstReport = record.visits().get(0).reports().get(0);
    assertEquals(SEEDED_TAG, firstReport.observations().get(0).details().get("seeded"));
  }
}
