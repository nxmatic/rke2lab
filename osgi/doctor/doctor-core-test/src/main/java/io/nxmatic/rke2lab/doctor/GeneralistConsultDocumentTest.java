package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import io.nxmatic.rke2lab.exchange.port.Coordinate;
import io.nxmatic.rke2lab.exchange.port.Document;
import io.nxmatic.rke2lab.exchange.port.Domain;
import io.nxmatic.rke2lab.exchange.port.ExchangeCatalog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Generalist's consult(Document) contract: map a checkpoint Document (carrying recordedAt + an
 * {@code observations} list, each in the flat {@code Observation.toOutputMap} shape) to a
 * consultation Document (narration + diagnosisAdoc + the structured reconstruction sub-trees). The
 * internal consult path (record-typed) is already tested; here we prove the wire-crossing Document
 * adapter — including that EVERY observation survives into the reconstructed record (the cluster's
 * N-phase case), so the seam loses no information.
 */
class GeneralistConsultDocumentTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "test");
  private static final Instant RECORDED_AT = Instant.parse("2026-06-28T00:00:00Z");
  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  void consult_document_returns_consultation_with_narration_and_diagnosis_block() {
    final Document checkpoint =
        checkpointWith(
            "systemd-adapter",
            Observation.failed(
                Symptom.CONNECTION_REFUSED, "connection refused", Map.of("source", "dbus")));

    final Document consultation = newGeneralist().consult(checkpoint);

    assertEquals(Domain.DOCTOR.slug(), consultation.domain(), "domain should be DOCTOR");
    assertEquals(
        Coordinate.CONSULTATION.slug(),
        consultation.coordinate(),
        "coordinate should be CONSULTATION");

    final JsonNode payload = parse(consultation.payload());
    assertEquals(
        "systemd-adapter",
        payload.path(ExchangeCatalog.FIELD_SCENARIO_ID).asText(),
        "scenarioId should be echoed");

    final String narration = payload.path(ExchangeCatalog.FIELD_NARRATION).asText();
    assertNotNull(narration, "narration should not be null");
    assertTrue(narration.length() > 0, "narration should be non-empty");

    final String diagnosisAdoc = payload.path(ExchangeCatalog.FIELD_DIAGNOSIS_ADOC).asText();
    assertTrue(
        diagnosisAdoc.contains("⚕ Diagnosis:"), "diagnosisAdoc should contain diagnosis marker");
    assertTrue(
        diagnosisAdoc.contains("🔬 Assessment"), "diagnosisAdoc should contain assessment marker");
  }

  @Test
  void consult_document_carries_structured_plan_and_expectations_for_reconstruction() {
    final Document checkpoint =
        checkpointWith(
            "systemd-adapter",
            Observation.failed(
                Symptom.CONNECTION_REFUSED, "connection refused", Map.of("source", "dbus")));

    final Document consultation = newGeneralist().consult(checkpoint);
    final JsonNode payload = parse(consultation.payload());

    final JsonNode reportNode = payload.path(ConsultationReport.OUTPUT_KEY);
    assertNotNull(reportNode, "consultation should carry the ConsultationReport sub-tree");

    @SuppressWarnings("unchecked")
    final Map<String, Object> reportMap = mapper.convertValue(reportNode, Map.class);
    final var reportOpt = ConsultationReportReader.fromOutputMap(reportMap);
    assertTrue(reportOpt.isPresent(), "ConsultationReport should round-trip successfully");

    final ConsultationReport reconstructed = reportOpt.get();
    assertEquals(
        "systemd-adapter", reconstructed.checkpointId(), "checkpointId should match the input");
    assertEquals(
        Symptom.CONNECTION_REFUSED, reconstructed.symptom(), "symptom should match the input");
    assertFalse(
        reconstructed.plan().replies().isEmpty(),
        "plan should have at least one reply from FakeSpecialist");

    final var expectationsNode = payload.path(Expectation.OUTPUT_KEY);
    assertNotNull(expectationsNode, "consultation should carry the expectations sub-tree");

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> expectationsList =
        mapper.convertValue(expectationsNode, List.class);
    assertFalse(expectationsList.isEmpty(), "expectations list should be non-empty");

    final var firstExpectationOpt = ExpectationReader.fromOutputMap(expectationsList.get(0));
    assertTrue(firstExpectationOpt.isPresent(), "first Expectation should round-trip successfully");
  }

  @Test
  void consult_document_keeps_every_observation_for_the_cluster_multi_phase_case() {
    // The cluster checkpoint carries one observation per phase; only one failed, but ALL must
    // survive into the reconstructed record (no information lost at the seam). OSGi routes on the
    // first observation that carries a symptom.
    final Document checkpoint =
        checkpointWith(
            "cluster-readiness",
            Observation.ok("kubeconfig published", Map.of("phase", "kubeconfig")),
            Observation.failed(
                Symptom.API_NOT_READY, "api server not ready", Map.of("phase", "api")),
            Observation.ok("controllers effective", Map.of("phase", "controllers")));

    final Document consultation = newGeneralist().consult(checkpoint);
    final JsonNode payload = parse(consultation.payload());

    @SuppressWarnings("unchecked")
    final Map<String, Object> reportMap =
        mapper.convertValue(payload.path(ConsultationReport.OUTPUT_KEY), Map.class);
    final var reportOpt = ConsultationReportReader.fromOutputMap(reportMap);
    assertTrue(reportOpt.isPresent(), "the cluster report should round-trip");

    final ConsultationReport reconstructed = reportOpt.get();
    assertEquals(
        Symptom.API_NOT_READY,
        reconstructed.symptom(),
        "routed on the first symptom-bearing observation");
    assertEquals(
        3,
        reconstructed.observations().size(),
        "ALL three phase observations must survive reconstruction (no info lost)");
  }

  private static Generalist newGeneralist() {
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(Generalist.GENERALIST_ID, PATIENT);
    final MedicalRecordRegistry registry = patient -> new MedicalRecord(patient, List.of());
    final ClinicalAccess access =
        new ClinicalAccess(Generalist.GENERALIST_ID, PATIENT, policy, registry, msg -> {});
    return Generalist.builder().specialists(List.of(new FakeSpecialist())).access(access).build();
  }

  /** A checkpoint Document carrying recordedAt + the observations list (each toOutputMap). */
  private static Document checkpointWith(String scenarioId, Observation... observations) {
    final ObjectNode payload = mapper.createObjectNode();
    payload.put(ExchangeCatalog.FIELD_SCENARIO_ID, scenarioId);
    payload.put(ExchangeCatalog.FIELD_RECORDED_AT, RECORDED_AT.toString());
    final ArrayNode array = payload.putArray(ExchangeCatalog.FIELD_OBSERVATIONS);
    for (Observation observation : observations) {
      array.add(mapper.valueToTree(observation.toOutputMap()));
    }
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), serialize(payload));
  }

  private static String serialize(JsonNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode parse(String payload) {
    try {
      return mapper.readTree(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
