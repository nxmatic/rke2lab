package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.internal.ClinicalAccess;
import io.nxmatic.rke2lab.doctor.internal.Generalist;
import io.nxmatic.rke2lab.doctor.internal.GrantPolicy;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import io.nxmatic.rke2lab.exchange.port.Coordinate;
import io.nxmatic.rke2lab.exchange.port.Document;
import io.nxmatic.rke2lab.exchange.port.Domain;
import io.nxmatic.rke2lab.exchange.port.ExchangeCatalog;
import io.nxmatic.rke2lab.exchange.port.SymptomKind;
import java.util.List;
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
}
