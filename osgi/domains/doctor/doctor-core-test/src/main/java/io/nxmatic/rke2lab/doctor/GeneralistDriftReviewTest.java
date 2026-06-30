package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.internal.*;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.ResolutionPredicate;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.records.Visit;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeneralistDriftReviewTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  private static ClinicalAccess minimalAccess() {
    final MedicalRecordRegistry registry = patient -> new MedicalRecord(patient, List.of());
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(Generalist.GENERALIST_ID, PATIENT);
    return new ClinicalAccess(Generalist.GENERALIST_ID, PATIENT, policy, registry, msg -> {});
  }

  private static ConsultationReport connectionRefusedReport() {
    final Prescription rx = new Prescription(RemediationProgramRef.RESTART_UNIT, Map.of(), "hint");
    final RemediationPlan plan =
        new RemediationPlan(
            Symptom.CONNECTION_REFUSED, List.of(ReferralReplies.treating(rx)), "summary");
    return new ConsultationReport("systemd-adapter", List.of(), plan);
  }

  @Test
  void resolvedExpectationIsReviewedAndExternalChangeInferred() {
    final List<Intervention> captured = new ArrayList<>();
    final DriftSpecialist drift = new DriftSpecialist(captured::add);
    final Generalist generalist =
        Generalist.builder()
            .specialists(List.of())
            .access(minimalAccess())
            .driftSpecialist(drift)
            .build();

    final ProblemRef problem =
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED);
    final Expectation expectation =
        new Expectation(
            problem,
            RemediationProgramRef.RESTART_UNIT,
            new ResolutionPredicate(Symptom.CONNECTION_REFUSED),
            Instant.ofEpochSecond(1));

    final Visit visit1 =
        new Visit(
            0, Instant.ofEpochSecond(1), List.of(connectionRefusedReport()), List.of(expectation));
    final Visit visit2clean = new Visit(1, Instant.ofEpochSecond(2), List.of(), List.of());

    final MedicalRecord record = new MedicalRecord(PATIENT, List.of(visit1, visit2clean));
    final List<ReferralReply> letters =
        generalist.reviewOpenProblems(record, InterventionLedger.empty());

    assertEquals(1, letters.size());
    assertEquals("drift/confounded-inferred/v1", letters.get(0).assessment().schemaRef().id());
    assertEquals(1, captured.size());
    assertEquals(Provenance.EXTERNAL_CHANGE_DETECTED, captured.get(0).provenance());
    assertEquals(problem, captured.get(0).problem());
  }

  @Test
  void unresolvedSymptomIsNotReviewed() {
    final ProblemRef problem =
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED);
    final Expectation expectation =
        new Expectation(
            problem,
            RemediationProgramRef.RESTART_UNIT,
            new ResolutionPredicate(Symptom.CONNECTION_REFUSED),
            Instant.ofEpochSecond(1));

    final Visit visit1 =
        new Visit(
            0, Instant.ofEpochSecond(1), List.of(connectionRefusedReport()), List.of(expectation));
    final Visit visit2dirty =
        new Visit(1, Instant.ofEpochSecond(2), List.of(connectionRefusedReport()), List.of());

    final MedicalRecord record2 = new MedicalRecord(PATIENT, List.of(visit1, visit2dirty));
    final List<Intervention> captured2 = new ArrayList<>();
    final Generalist g2 =
        Generalist.builder()
            .specialists(List.of())
            .access(minimalAccess())
            .driftSpecialist(new DriftSpecialist(captured2::add))
            .build();

    final List<ReferralReply> none = g2.reviewOpenProblems(record2, InterventionLedger.empty());

    assertTrue(none.isEmpty(), "nothing resolved → nothing to review");
    assertTrue(captured2.isEmpty());
  }
}
