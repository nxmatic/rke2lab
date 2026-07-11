package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.ConsultationReport;
import io.nxmatic.rke2lab.doctor.contract.Expectation;
import io.nxmatic.rke2lab.doctor.contract.Intervention;
import io.nxmatic.rke2lab.doctor.contract.InterventionLedger;
import io.nxmatic.rke2lab.doctor.contract.MedicalRecord;
import io.nxmatic.rke2lab.doctor.contract.Patient;
import io.nxmatic.rke2lab.doctor.contract.Prescription;
import io.nxmatic.rke2lab.doctor.contract.ProblemRef;
import io.nxmatic.rke2lab.doctor.contract.Provenance;
import io.nxmatic.rke2lab.doctor.contract.ReferralReply;
import io.nxmatic.rke2lab.doctor.contract.RemediationPlan;
import io.nxmatic.rke2lab.doctor.contract.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.contract.ResolutionPredicate;
import io.nxmatic.rke2lab.doctor.contract.Symptom;
import io.nxmatic.rke2lab.doctor.contract.Visit;
import io.nxmatic.rke2lab.doctor.internal.ClinicalAccess;
import io.nxmatic.rke2lab.doctor.internal.DriftSpecialist;
import io.nxmatic.rke2lab.doctor.internal.Generalist;
import io.nxmatic.rke2lab.doctor.internal.GrantPolicy;
import io.nxmatic.rke2lab.doctor.internal.InterventionLedgerRegistry;
import io.nxmatic.rke2lab.doctor.internal.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
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

  /**
   * A capturing {@link InterventionLedgerRegistry} — the mock the drift specialist records into.
   */
  private static final class CapturingLedger implements InterventionLedgerRegistry {
    private final List<Intervention> recorded = new ArrayList<>();

    @Override
    public InterventionLedger ledger() {
      return new InterventionLedger(List.copyOf(recorded));
    }

    @Override
    public void record(Intervention intervention) {
      recorded.add(intervention);
    }
  }

  @Test
  void resolvedExpectationIsReviewedAndExternalChangeInferred() {
    final CapturingLedger captured = new CapturingLedger();
    final DriftSpecialist drift = new DriftSpecialist(captured);
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
    assertEquals(1, captured.recorded.size());
    final Intervention inferred = captured.recorded.get(0);
    assertEquals(Provenance.EXTERNAL_CHANGE_DETECTED, inferred.provenance());
    assertEquals(problem, inferred.problem());
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
    final CapturingLedger captured2 = new CapturingLedger();
    final Generalist g2 =
        Generalist.builder()
            .specialists(List.of())
            .access(minimalAccess())
            .driftSpecialist(new DriftSpecialist(captured2))
            .build();

    final List<ReferralReply> none = g2.reviewOpenProblems(record2, InterventionLedger.empty());

    assertTrue(none.isEmpty(), "nothing resolved → nothing to review");
    assertTrue(captured2.recorded.isEmpty());
  }
}
