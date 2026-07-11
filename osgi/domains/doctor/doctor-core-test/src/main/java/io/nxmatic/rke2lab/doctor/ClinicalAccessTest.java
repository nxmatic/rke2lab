package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.contract.ClinicianId;
import io.nxmatic.rke2lab.doctor.contract.MedicalRecord;
import io.nxmatic.rke2lab.doctor.contract.Patient;
import io.nxmatic.rke2lab.doctor.internal.ClinicalAccess;
import io.nxmatic.rke2lab.doctor.internal.GrantPolicy;
import io.nxmatic.rke2lab.doctor.internal.MedicalRecordRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClinicalAccessTest {

  private static final ClinicianId GEN = new ClinicianId("generalist");
  private static final Patient DEV = new Patient("organization", "rke2lab", "dev");
  private static final Patient PEER = new Patient("organization", "rke2lab", "peer");

  /** A registry that knows two siblings and returns a one-visit record for each. */
  private static MedicalRecordRegistry twoPatientRegistry() {
    return new MedicalRecordRegistry() {
      @Override
      public MedicalRecord recordFor(Patient patient) {
        return new MedicalRecord(patient, List.of());
      }

      @Override
      public List<MedicalRecord> cohortFor(Patient current) {
        return List.of(recordFor(DEV), recordFor(PEER));
      }
    };
  }

  @Test
  void record_of_the_admitted_patient_is_served_when_self_granted() {
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(GEN, DEV);
    final ClinicalAccess access =
        new ClinicalAccess(GEN, DEV, policy, twoPatientRegistry(), msg -> {});
    assertEquals(DEV, access.record().patient());
  }

  @Test
  void cohort_returns_only_granted_siblings() {
    // Granted on DEV (self) + PEER (cohort) → both visible.
    final GrantPolicy granted =
        GrantPolicy.empty().withSelfGrant(GEN, DEV).withCohortGrant(GEN, List.of(DEV, PEER));
    final ClinicalAccess all =
        new ClinicalAccess(GEN, DEV, granted, twoPatientRegistry(), msg -> {});
    assertEquals(2, all.cohort().size());

    // Granted on DEV only → PEER is filtered out of the cohort (the gate bites the cohort).
    final GrantPolicy restricted = GrantPolicy.empty().withSelfGrant(GEN, DEV);
    final ClinicalAccess limited =
        new ClinicalAccess(GEN, DEV, restricted, twoPatientRegistry(), msg -> {});
    assertEquals(1, limited.cohort().size());
    assertEquals(DEV, limited.cohort().get(0).patient());
  }

  @Test
  void an_ungranted_read_degrades_to_empty_and_logs() {
    final StringBuilder log = new StringBuilder();
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(GEN, DEV);
    final ClinicalAccess access =
        new ClinicalAccess(GEN, DEV, policy, twoPatientRegistry(), log::append);
    final MedicalRecord denied = access.record(PEER);
    assertTrue(denied.visits().isEmpty(), "ungranted read yields an empty record");
    assertTrue(log.length() > 0, "the refusal is logged");
  }
}
