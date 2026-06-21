package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.Checkpoint;
import io.nxmatic.rke2lab.doctor.ClinicalAccess;
import io.nxmatic.rke2lab.doctor.ConsultationReport;
import io.nxmatic.rke2lab.doctor.DriftSpecialist;
import io.nxmatic.rke2lab.doctor.Expectation;
import io.nxmatic.rke2lab.doctor.Generalist;
import io.nxmatic.rke2lab.doctor.GrantPolicy;
import io.nxmatic.rke2lab.doctor.Intervention;
import io.nxmatic.rke2lab.doctor.InterventionLedger;
import io.nxmatic.rke2lab.doctor.MedicalRecord;
import io.nxmatic.rke2lab.doctor.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.Patient;
import io.nxmatic.rke2lab.doctor.Prescription;
import io.nxmatic.rke2lab.doctor.ProblemRef;
import io.nxmatic.rke2lab.doctor.Provenance;
import io.nxmatic.rke2lab.doctor.ReferralReply;
import io.nxmatic.rke2lab.doctor.RemediationPlan;
import io.nxmatic.rke2lab.doctor.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.ResolutionPredicate;
import io.nxmatic.rke2lab.doctor.Symptom;
import io.nxmatic.rke2lab.doctor.Visit;
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
