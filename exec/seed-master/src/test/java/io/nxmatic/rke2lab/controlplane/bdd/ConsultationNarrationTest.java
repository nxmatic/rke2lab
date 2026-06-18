package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsultationNarrationTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  @Test
  void renders_the_consulted_line_from_an_empty_record() {
    final MedicalRecord record = new MedicalRecord(PATIENT, List.of());
    assertEquals(
        "consulted with 0 prior visit(s); connection-refused seen 0× before",
        ConsultationNarration.consultedLine(record, Symptom.CONNECTION_REFUSED));
  }

  @Test
  void renders_the_consulted_line_from_a_record_with_history() {
    // Build a record with 2 visits: one raises CONNECTION_REFUSED, the other raises TIMEOUT.
    // This proves the fold counts visits with the symptom, not all visits.
    final ConsultationReport connectionRefusedReport = report(Symptom.CONNECTION_REFUSED);
    final ConsultationReport timeoutReport = report(Symptom.TIMEOUT);

    final Visit visit1 =
        new Visit(
            1, Instant.ofEpochSecond(1_780_000_001L), List.of(connectionRefusedReport), List.of());
    final Visit visit2 =
        new Visit(2, Instant.ofEpochSecond(1_780_000_002L), List.of(timeoutReport), List.of());

    final MedicalRecord record = new MedicalRecord(PATIENT, List.of(visit1, visit2));

    // Expected: 2 total visits, but CONNECTION_REFUSED raised in only 1 of them.
    assertEquals(
        "consulted with 2 prior visit(s); connection-refused seen 1× before",
        ConsultationNarration.consultedLine(record, Symptom.CONNECTION_REFUSED));
  }

  private static ConsultationReport report(Symptom symptom) {
    final Observation observation = Observation.failed(symptom, "test " + symptom.id(), Map.of());
    final RemediationPlan plan = new RemediationPlan(symptom, List.of(), "test summary");
    return new ConsultationReport("test-checkpoint", List.of(observation), plan);
  }
}
