package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.internal.ClinicalAccess;
import io.nxmatic.rke2lab.doctor.internal.Generalist;
import io.nxmatic.rke2lab.doctor.internal.GrantPolicy;
import io.nxmatic.rke2lab.doctor.internal.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Consultation;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ObservationWire;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessCheckpoint;
import io.nxmatic.rke2lab.world.gateway.port.SymptomKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private static final DocumentCodec codec = new DocumentCodec();

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

    final Consultation decoded = codec.decode(consultation, Consultation.class);
    assertEquals("systemd-adapter", decoded.scenarioId(), "scenarioId should be echoed");

    assertNotNull(decoded.narration(), "narration should not be null");
    assertTrue(decoded.narration().length() > 0, "narration should be non-empty");

    assertTrue(
        decoded.diagnosisAdoc().contains("⚕ Diagnosis:"),
        "diagnosisAdoc should contain diagnosis marker");
    assertTrue(
        decoded.diagnosisAdoc().contains("🔬 Assessment"),
        "diagnosisAdoc should contain assessment marker");
  }

  @Test
  void consult_document_carries_structured_plan_and_expectations_for_reconstruction() {
    final Document checkpoint =
        checkpointWith(
            "systemd-adapter",
            Observation.failed(
                Symptom.CONNECTION_REFUSED, "connection refused", Map.of("source", "dbus")));

    final Document consultation = newGeneralist().consult(checkpoint);
    final Consultation decoded = codec.decode(consultation, Consultation.class);

    final ConsultationReport reconstructed =
        codec.fromMap(decoded.consultationReport(), ConsultationReport.class);
    assertEquals(
        "systemd-adapter", reconstructed.checkpointId(), "checkpointId should match the input");
    assertEquals(
        Symptom.CONNECTION_REFUSED, reconstructed.symptom(), "symptom should match the input");
    assertFalse(
        reconstructed.plan().replies().isEmpty(),
        "plan should have at least one reply from FakeSpecialist");

    assertFalse(decoded.expectations().isEmpty(), "expectations list should be non-empty");

    final Expectation firstExpectation =
        codec.fromMap(decoded.expectations().get(0), Expectation.class);
    assertEquals(
        Symptom.CONNECTION_REFUSED,
        firstExpectation.symptom(),
        "first Expectation should round-trip successfully");
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
    final Consultation decoded = codec.decode(consultation, Consultation.class);

    final ConsultationReport reconstructed =
        codec.fromMap(decoded.consultationReport(), ConsultationReport.class);
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

  /**
   * A consult checkpoint Document carrying recordedAt + the observations, encoded via the codec.
   */
  private static Document checkpointWith(String scenarioId, Observation... observations) {
    final List<ObservationWire> wires =
        List.of(observations).stream().map(GeneralistConsultDocumentTest::toWire).toList();
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            scenarioId, Optional.empty(), Optional.empty(), Optional.of(RECORDED_AT), wires);
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), codec.encode(checkpoint));
  }

  /**
   * The doctor {@link Observation} as its seam {@link ObservationWire} twin (symptom id → slug).
   */
  private static ObservationWire toWire(Observation observation) {
    final Optional<SymptomKind> symptom =
        observation.symptom().flatMap(s -> SymptomKind.parse(s.id()));
    return new ObservationWire(
        observation.status(), observation.summary(), symptom, observation.details());
  }
}
