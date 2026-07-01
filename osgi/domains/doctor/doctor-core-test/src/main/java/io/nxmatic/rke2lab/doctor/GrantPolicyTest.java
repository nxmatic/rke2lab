package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.internal.GrantPolicy;
import io.nxmatic.rke2lab.doctor.records.ClinicianId;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.util.List;
import org.junit.jupiter.api.Test;

class GrantPolicyTest {

  private static final ClinicianId GEN = new ClinicianId("generalist");
  private static final Patient DEV = new Patient("organization", "rke2lab", "dev");
  private static final Patient PEER = new Patient("organization", "rke2lab", "peer");
  private static final Patient STRANGER = new Patient("organization", "rke2lab", "stranger");

  @Test
  void admission_self_grants_the_generalist_on_the_admitted_patient() {
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(GEN, DEV);
    assertTrue(policy.isGranted(GEN, DEV));
  }

  @Test
  void a_clinician_is_not_granted_a_patient_it_holds_no_grant_for() {
    final GrantPolicy policy = GrantPolicy.empty().withSelfGrant(GEN, DEV);
    assertFalse(policy.isGranted(GEN, STRANGER));
  }

  @Test
  void cohort_grants_the_listed_siblings() {
    final GrantPolicy policy =
        GrantPolicy.empty().withSelfGrant(GEN, DEV).withCohortGrant(GEN, List.of(DEV, PEER));
    assertTrue(policy.isGranted(GEN, PEER));
  }

  @Test
  void a_restricted_policy_excludes_a_sibling_it_was_not_granted() {
    // The negative test that proves the gate is REAL, not decorative: PEER exists in the backend
    // but the policy was not given a grant for it, so isGranted is false.
    final GrantPolicy policy = GrantPolicy.empty().withCohortGrant(GEN, List.of(DEV));
    assertFalse(policy.isGranted(GEN, PEER));
  }
}
