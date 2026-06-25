package io.nxmatic.rke2lab.pulumi.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.records.SymptomHistory;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import io.nxmatic.rke2lab.pulumi.edge.testkit.StackHistoryFixture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveMedicalRecordRegistryTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SEEDED_TAG = "synthetic — injected for the live-registry check";

  @TempDir Path tempDir;

  @Test
  void backendDirFromUrlStripsFileSchemeAndRejectsOthers() {
    assertEquals(
        Path.of("/tmp/x/.pulumi-state"),
        LiveMedicalRecordRegistry.backendDirFromUrl("file:///tmp/x/.pulumi-state"));
    assertNull(LiveMedicalRecordRegistry.backendDirFromUrl("s3://bucket"));
    assertNull(LiveMedicalRecordRegistry.backendDirFromUrl("https://app.pulumi.com"));
    assertNull(LiveMedicalRecordRegistry.backendDirFromUrl(null));
  }

  @Test
  void degradesToEmptyAndLogsAReasonWhenBackendIsMissing() {
    final List<String> log = new ArrayList<>();
    final Patient patient = new Patient("organization", "rke2lab", "seeded");
    final LiveMedicalRecordRegistry registry =
        new LiveMedicalRecordRegistry(tempDir.resolve("does-not-exist"), log::add);

    final MedicalRecord record = registry.recordFor(patient);

    assertTrue(record.visits().isEmpty());
    assertTrue(log.stream().anyMatch(line -> line != null && !line.isBlank()));
  }

  @Test
  void degradesToEmptyAndLogsAReasonWhenBackendDirIsNull() {
    final List<String> log = new ArrayList<>();
    final Patient patient = new Patient("organization", "rke2lab", "seeded");
    final LiveMedicalRecordRegistry registry = new LiveMedicalRecordRegistry(null, log::add);

    final MedicalRecord record = registry.recordFor(patient);

    assertTrue(record.visits().isEmpty());
    assertTrue(log.stream().anyMatch(line -> line != null && !line.isBlank()));
  }

  @Test
  void reconstructsARealRecordFromASeededBackend() throws Exception {
    final StackHistoryFixture fixture =
        StackHistoryFixture.at(tempDir, "rke2lab", "seeded")
            .updateWithLatest(
                1_780_000_001L,
                latestWith(
                    seededReport(Symptom.CONNECTION_REFUSED, RemediationProgramRef.RESTART_UNIT)))
            .updateWithLatest(
                1_780_000_002L, latestWith(seededReport(Symptom.CONNECTION_REFUSED, null)));

    final List<String> log = new ArrayList<>();
    final Patient patient = new Patient("organization", "rke2lab", "seeded");
    final LiveMedicalRecordRegistry registry =
        new LiveMedicalRecordRegistry(fixture.backendDir(), log::add);

    final MedicalRecord record = registry.recordFor(patient);

    assertFalse(record.visits().isEmpty());
    final SymptomHistory history = record.historyOf(Symptom.CONNECTION_REFUSED);
    assertEquals(2, history.count());
    assertTrue(history.isChronic());
  }

  @Test
  void memoizesPerPatientSoASecondReadReturnsTheSameInstance() {
    final List<String> log = new ArrayList<>();
    final Patient patient = new Patient("organization", "rke2lab", "seeded");
    final LiveMedicalRecordRegistry registry =
        new LiveMedicalRecordRegistry(tempDir.resolve("does-not-exist"), log::add);

    final MedicalRecord first = registry.recordFor(patient);
    final int logLinesAfterFirst = log.size();
    final MedicalRecord second = registry.recordFor(patient);

    assertSame(first, second);
    assertEquals(logLinesAfterFirst, log.size());
  }

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
}
