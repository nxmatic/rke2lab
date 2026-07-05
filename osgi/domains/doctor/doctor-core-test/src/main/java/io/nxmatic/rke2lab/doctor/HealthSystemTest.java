package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.internal.ConsultationDag;
import io.nxmatic.rke2lab.doctor.internal.Generalist;
import io.nxmatic.rke2lab.doctor.internal.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.spi.ClinicalReasoning;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.doctor.testkit.FakeSpecialist;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.util.List;
import java.util.Map;
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

  // The single construction path returns the ConsultingService seam; the white-box actor tests
  // reach
  // the bundle-internal Generalist (recordForCurrentPatient is OFF the seam now — no record crosses
  // to the host) by casting, since this fragment shares doctor-core's loader.
  private static Generalist admit(Patient patient, MedicalRecordRegistry registry) {
    return (Generalist)
        ConsultationDag.assemble(patient, registry, noopLedger(), null, roster(), msg -> {});
  }

  @Test
  void admit_employs_a_doctor_that_can_read_its_own_patient() {
    final Generalist doctor = admit(DEV, singlePatientRegistry());
    assertNotNull(doctor);
    assertEquals(DEV, doctor.recordForCurrentPatient().patient());
  }

  @Test
  void the_employed_doctor_still_consults_normally() {
    final ConsultingService doctor = admit(DEV, singlePatientRegistry());
    final Observation observation =
        Observation.failed(Symptom.CONNECTION_REFUSED, "dbus refused", Map.of());
    final RemediationPlan plan =
        doctor
            .adapt(ClinicalReasoning.class)
            .orElseThrow()
            .consult(Symptom.CONNECTION_REFUSED, observation);
    assertEquals(Symptom.CONNECTION_REFUSED, plan.symptom());
    assertTrue(plan.hasPrescriptions(), "the dbus specialist treats connection-refused");
  }

  @Test
  void the_admitted_doctor_reads_its_own_patient_but_a_stranger_is_outside_the_cohort() {
    // cohortFor surfaces only DEV at admission, so only DEV is granted.
    final Generalist doctor = admit(DEV, singlePatientRegistry());
    // Self-read works (admitted + self-granted).
    assertEquals(DEV, doctor.recordForCurrentPatient().patient());
    // The cohort is exactly the admitted patient — no ungranted stranger leaks in.
    assertTrue(
        doctor
            .adapt(ClinicalReasoning.class)
            .orElseThrow()
            .cohortFinding(Symptom.CONNECTION_REFUSED)
            .contains("of 1 patient(s)"),
        "the cohort is the single admitted, self-granted patient");
  }
}
