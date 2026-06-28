package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.internal.ClinicalAccess;
import io.nxmatic.rke2lab.doctor.internal.Generalist;
import io.nxmatic.rke2lab.doctor.internal.GrantPolicy;
import io.nxmatic.rke2lab.doctor.port.ConsultationReportReader;
import io.nxmatic.rke2lab.doctor.port.ExpectationReader;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import io.nxmatic.rke2lab.exchange.port.Coordinate;
import io.nxmatic.rke2lab.exchange.port.Document;
import io.nxmatic.rke2lab.exchange.port.Domain;
import io.nxmatic.rke2lab.exchange.port.ExchangeCatalog;
import io.nxmatic.rke2lab.exchange.port.SymptomKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Generalist's consult(Document) contract: map a checkpoint Document (with symptomKind +
 * summary + details) to a consultation Document (with narration + diagnosisAdoc). The internal
 * consult path (record-typed) is already tested; here we prove the wire-crossing Document adapter.
 */
class GeneralistConsultDocumentTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "test");
  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  void consult_document_returns_consultation_with_narration_and_diagnosis_block() {
    // Build a checkpoint Document with CONNECTION_REFUSED symptom
    final ObjectNode checkpointPayload = mapper.createObjectNode();
    checkpointPayload.put(ExchangeCatalog.FIELD_SCENARIO_ID, "systemd-adapter");
    checkpointPayload.put(ExchangeCatalog.FIELD_FAILED, true);
    checkpointPayload.put(
        ExchangeCatalog.FIELD_SYMPTOM_KIND, SymptomKind.CONNECTION_REFUSED.slug());
    checkpointPayload.put(ExchangeCatalog.FIELD_SUMMARY, "connection refused");
    checkpointPayload.put(ExchangeCatalog.FIELD_DETAILS, "dbus endpoint unreachable");
    checkpointPayload.put(
        ExchangeCatalog.FIELD_RECORDED_AT, Instant.parse("2026-06-28T00:00:00Z").toString());
    final Document checkpoint =
        new Document(
            Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), checkpointPayload);

    // Assemble a Generalist with a prescribing FakeSpecialist (so plan has prescriptions)
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(Generalist.GENERALIST_ID, PATIENT);
    final MedicalRecordRegistry registry = patient -> new MedicalRecord(patient, List.of());
    final ClinicalAccess access =
        new ClinicalAccess(Generalist.GENERALIST_ID, PATIENT, policy, registry, msg -> {});
    final List<Specialist> specialists = List.of(new FakeSpecialist());
    final Generalist generalist =
        Generalist.builder().specialists(specialists).access(access).build();

    // Call consult(Document)
    final Document consultation = generalist.consult(checkpoint);

    // Assert: domain + coordinate match consultation
    assertEquals(Domain.DOCTOR.slug(), consultation.domain(), "domain should be DOCTOR");
    assertEquals(
        Coordinate.CONSULTATION.slug(),
        consultation.coordinate(),
        "coordinate should be CONSULTATION");

    // Assert: scenarioId echoed
    final var consultationPayload = consultation.payload();
    assertEquals(
        "systemd-adapter",
        consultationPayload.path(ExchangeCatalog.FIELD_SCENARIO_ID).asText(),
        "scenarioId should be echoed");

    // Assert: narration is non-empty
    final String narration = consultationPayload.path(ExchangeCatalog.FIELD_NARRATION).asText();
    assertNotNull(narration, "narration should not be null");
    assertTrue(narration.length() > 0, "narration should be non-empty");

    // Assert: diagnosisAdoc contains the diagnosis block markers
    final String diagnosisAdoc =
        consultationPayload.path(ExchangeCatalog.FIELD_DIAGNOSIS_ADOC).asText();
    assertNotNull(diagnosisAdoc, "diagnosisAdoc should not be null");
    assertTrue(
        diagnosisAdoc.contains("⚕ Diagnosis:"), "diagnosisAdoc should contain diagnosis marker");
    assertTrue(
        diagnosisAdoc.contains("🔬 Assessment"), "diagnosisAdoc should contain assessment marker");
  }

  @Test
  void consult_document_carries_structured_plan_and_expectations_for_reconstruction() {
    // Build a checkpoint Document with CONNECTION_REFUSED symptom and recordedAt timestamp
    final Instant recordedAt = Instant.parse("2026-06-28T00:00:00Z");
    final ObjectNode checkpointPayload = mapper.createObjectNode();
    checkpointPayload.put(ExchangeCatalog.FIELD_SCENARIO_ID, "systemd-adapter");
    checkpointPayload.put(ExchangeCatalog.FIELD_FAILED, true);
    checkpointPayload.put(
        ExchangeCatalog.FIELD_SYMPTOM_KIND, SymptomKind.CONNECTION_REFUSED.slug());
    checkpointPayload.put(ExchangeCatalog.FIELD_SUMMARY, "connection refused");
    checkpointPayload.put(ExchangeCatalog.FIELD_DETAILS, "dbus endpoint unreachable");
    checkpointPayload.put(ExchangeCatalog.FIELD_RECORDED_AT, recordedAt.toString());
    final Document checkpoint =
        new Document(
            Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), checkpointPayload);

    // Assemble a Generalist with a prescribing FakeSpecialist (so plan has prescriptions →
    // non-empty expectations)
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(Generalist.GENERALIST_ID, PATIENT);
    final MedicalRecordRegistry registry = patient -> new MedicalRecord(patient, List.of());
    final ClinicalAccess access =
        new ClinicalAccess(Generalist.GENERALIST_ID, PATIENT, policy, registry, msg -> {});
    final List<Specialist> specialists = List.of(new FakeSpecialist());
    final Generalist generalist =
        Generalist.builder().specialists(specialists).access(access).build();

    // Call consult(Document)
    final Document consultation = generalist.consult(checkpoint);

    // Extract the ConsultationReport sub-tree and round-trip it through the reader
    final JsonNode consultationPayload = consultation.payload();
    final JsonNode reportNode = consultationPayload.path(ConsultationReport.OUTPUT_KEY);
    assertNotNull(reportNode, "consultation should carry the ConsultationReport sub-tree");

    @SuppressWarnings("unchecked")
    final Map<String, Object> reportMap = mapper.convertValue(reportNode, Map.class);
    final var reportOpt = ConsultationReportReader.fromOutputMap(reportMap);
    assertTrue(reportOpt.isPresent(), "ConsultationReport should round-trip successfully");

    final ConsultationReport reconstructed = reportOpt.get();
    assertEquals(
        "systemd-adapter",
        reconstructed.checkpointId(),
        "checkpointId should match the input scenario");
    assertEquals(
        "CONNECTION_REFUSED",
        reconstructed.symptom().id(),
        "symptom should match the input symptom kind");
    assertFalse(
        reconstructed.plan().replies().isEmpty(),
        "plan should have at least one reply from FakeSpecialist");

    // Extract the Expectation sub-tree and verify it's non-empty and round-trips
    final var expectationsNode = consultationPayload.path(Expectation.OUTPUT_KEY);
    assertNotNull(expectationsNode, "consultation should carry the expectations sub-tree");

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> expectationsList =
        mapper.convertValue(expectationsNode, List.class);
    assertFalse(expectationsList.isEmpty(), "expectations list should be non-empty");

    final var firstExpectationOpt = ExpectationReader.fromOutputMap(expectationsList.get(0));
    assertTrue(firstExpectationOpt.isPresent(), "first Expectation should round-trip successfully");
  }
}
