package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.internal.*;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Admission over the single construction path ({@link ConsultationDag}): the patient is admitted,
 * the generalist employed with a credentialed access, and the roster consulted. Exercises the same
 * single construction path the OSGi {@code DefaultHealthSystem} routes through on admission.
 */
class HealthSystemTest {

  private static final Patient DEV = new Patient("organization", "rke2lab", "dev");

  private static MedicalRecordRegistry singlePatientRegistry() {
    return patient -> new MedicalRecord(patient, List.of());
  }

  private static List<Specialist> roster() {
    return List.of(new FakeSpecialist());
  }

  private static InterventionLedgerWriter noopLedger() {
    return intervention -> {};
  }

  private static ConsultingService admit(Patient patient, MedicalRecordRegistry registry) {
    return ConsultationDag.assemble(patient, registry, noopLedger(), roster(), msg -> {});
  }

  @Test
  void admit_employs_a_doctor_that_can_read_its_own_patient() {
    final ConsultingService doctor = admit(DEV, singlePatientRegistry());
    assertNotNull(doctor);
    assertEquals(DEV, doctor.recordForCurrentPatient().patient());
  }

  @Test
  void the_employed_doctor_still_consults_normally() {
    final ConsultingService doctor = admit(DEV, singlePatientRegistry());
    final Observation observation =
        Observation.failed(Symptom.CONNECTION_REFUSED, "dbus refused", java.util.Map.of());
    final RemediationPlan plan = doctor.consult(Symptom.CONNECTION_REFUSED, observation);
    assertEquals(Symptom.CONNECTION_REFUSED, plan.symptom());
    assertTrue(plan.hasPrescriptions(), "the dbus specialist treats connection-refused");
  }

  @Test
  void the_admitted_doctor_reads_its_own_patient_but_a_stranger_is_outside_the_cohort() {
    // cohortFor surfaces only DEV at admission, so only DEV is granted.
    final ConsultingService doctor = admit(DEV, singlePatientRegistry());
    // Self-read works (admitted + self-granted).
    assertEquals(DEV, doctor.recordForCurrentPatient().patient());
    // The cohort is exactly the admitted patient — no ungranted stranger leaks in.
    assertTrue(
        doctor.cohortFinding(Symptom.CONNECTION_REFUSED).contains("of 1 patient(s)"),
        "the cohort is the single admitted, self-granted patient");
  }
}
