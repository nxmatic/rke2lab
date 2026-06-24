package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.doctor.port.ClinicianId;
import io.nxmatic.rke2lab.doctor.port.MedicalRecord;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.port.Patient;
import io.nxmatic.rke2lab.doctor.port.Specialist;
import java.util.List;
import java.util.function.Consumer;

/**
 * The per-run keystone of the doctor model: it holds the shared {@link MedicalRecordRegistry} and a
 * {@link GrantPolicy}, admits the current {@link Patient}, and employs the clinicians — minting
 * each record-reading clinician a {@link ClinicalAccess} bound to its {@link ClinicianId}. Built
 * once at the readiness transition; shared by identity (same code, same ids in every run), not by a
 * store.
 *
 * <p>Today only the {@link Generalist} reads records, so only it is employed with an access. A
 * specialist carries a {@link ClinicianId} (its identity) but gains an access in step 2, when a
 * referred record is actually read.
 */
final class HealthSystem {

  private final Generalist generalist;

  private HealthSystem(Generalist generalist) {
    this.generalist = generalist;
  }

  /**
   * Build the keystone: hold the registry, admit the patient (mint this run's grants), employ the
   * generalist (holding the run's {@link DriftSpecialist}) with a credentialed access bound to its
   * id.
   */
  public static HealthSystem admit(
      Patient patient,
      MedicalRecordRegistry registry,
      List<Specialist> specialists,
      DriftSpecialist driftSpecialist,
      Consumer<String> logger) {
    final ClinicianId generalistId = Generalist.GENERALIST_ID;
    final List<Patient> cohortPatients =
        registry.cohortFor(patient).stream().map(MedicalRecord::patient).toList();
    final GrantPolicy policy =
        GrantPolicy.empty()
            .withSelfGrant(generalistId, patient)
            .withCohortGrant(generalistId, cohortPatients);
    final ClinicalAccess access =
        new ClinicalAccess(generalistId, patient, policy, registry, logger);
    return new HealthSystem(
        Generalist.builder()
            .specialists(specialists)
            .access(access)
            .driftSpecialist(driftSpecialist)
            .build());
  }

  /** The employed generalist a stage consults. */
  public Generalist generalist() {
    return generalist;
  }
}
