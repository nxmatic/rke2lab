package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.port.Specialist;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthSystemTest {

  private static final Patient DEV = new Patient("organization", "rke2lab", "dev");

  private static MedicalRecordRegistry singlePatientRegistry() {
    return patient -> new MedicalRecord(patient, List.of());
  }

  private static List<Specialist> roster() {
    return List.of(new FakeSpecialist());
  }

  private static DriftSpecialist noopDrift() {
    return new DriftSpecialist(intervention -> {});
  }

  @Test
  void admit_employs_a_generalist_that_can_read_its_own_patient() {
    final HealthSystem hs =
        HealthSystem.admit(DEV, singlePatientRegistry(), roster(), noopDrift(), msg -> {});
    final Generalist generalist = hs.generalist();
    assertNotNull(generalist);
    assertEquals(DEV, generalist.recordForCurrentPatient().patient());
  }

  @Test
  void the_employed_generalist_still_consults_normally() {
    final HealthSystem hs =
        HealthSystem.admit(DEV, singlePatientRegistry(), roster(), noopDrift(), msg -> {});
    final Observation observation =
        Observation.failed(Symptom.CONNECTION_REFUSED, "dbus refused", java.util.Map.of());
    final RemediationPlan plan = hs.generalist().consult(Symptom.CONNECTION_REFUSED, observation);
    assertEquals(Symptom.CONNECTION_REFUSED, plan.symptom());
    assertTrue(plan.hasPrescriptions(), "the dbus specialist treats connection-refused");
  }

  @Test
  void the_admitted_generalist_reads_its_own_patient_but_a_stranger_is_outside_the_cohort() {
    // cohortFor surfaces only DEV at admission, so only DEV is granted.
    final MedicalRecordRegistry registry = patient -> new MedicalRecord(patient, List.of());
    final HealthSystem hs = HealthSystem.admit(DEV, registry, roster(), noopDrift(), msg -> {});
    // Self-read works (admitted + self-granted).
    assertEquals(DEV, hs.generalist().recordForCurrentPatient().patient());
    // The cohort is exactly the admitted patient — no ungranted stranger leaks in.
    assertTrue(
        hs.generalist().cohortFinding(Symptom.CONNECTION_REFUSED).contains("of 1 patient(s)"),
        "the cohort is the single admitted, self-granted patient");
  }
}
