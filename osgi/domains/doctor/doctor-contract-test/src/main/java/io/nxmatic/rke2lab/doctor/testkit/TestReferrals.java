package io.nxmatic.rke2lab.doctor.testkit;

import io.nxmatic.rke2lab.doctor.contract.MedicalRecord;
import io.nxmatic.rke2lab.doctor.contract.Observation;
import io.nxmatic.rke2lab.doctor.contract.Patient;
import io.nxmatic.rke2lab.doctor.contract.Referral;
import io.nxmatic.rke2lab.doctor.contract.Symptom;
import java.util.List;

/**
 * Test-only factory for {@link Referral} fixtures — the request a specialist's {@code diagnose} is
 * exercised with. A specialist test only needs "a referral carrying this symptom + observation for
 * some patient with an empty record"; this supplies it from one place so each domain's specialist
 * test (systemd / netplan / cluster) does not re-spell the {@code Referral.of(...)} + empty-record
 * boilerplate. The patient and the empty record are irrelevant to a pure diagnose — only the
 * symptom and the observation it reads matter.
 */
public final class TestReferrals {

  /** A placeholder patient — its identity is irrelevant to a specialist's pure diagnose. */
  public static final Patient PATIENT = new Patient("organization", "rke2lab", "test");

  private TestReferrals() {}

  /** A referral for {@code symptom} carrying {@code observation}, over an empty record. */
  public static Referral of(Symptom symptom, Observation observation) {
    return Referral.of(PATIENT, symptom, observation, new MedicalRecord(PATIENT, List.of()));
  }
}
