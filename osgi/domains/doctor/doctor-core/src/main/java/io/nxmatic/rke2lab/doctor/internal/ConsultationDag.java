package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.contract.ClinicianId;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.MedicalRecord;
import io.nxmatic.rke2lab.doctor.contract.Patient;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.List;
import java.util.function.Consumer;

/**
 * The single construction path for the consultation DAG: admit the patient (mint this run's self +
 * cohort grants), bind a credentialed {@link ClinicalAccess}, and employ the {@link Generalist}
 * over the roster + the run's {@link DriftSpecialist}. Used by the OSGi {@code DefaultHealthSystem}
 * (the institution, specialists by DS) for live admission, and by the {@code ExactRosterDoctor}
 * fixture in the doctor-core-test fragment (which shares this loader, so it reaches this sealed
 * package white-box) for tests that drive the DAG over an exact roster — so admission lives in
 * exactly one place. Package-private actors stay hidden behind the {@link ConsultingService} it
 * returns.
 */
public final class ConsultationDag {

  private ConsultationDag() {}

  /**
   * Admit the patient over the EXACT roster + the intervention-ledger cellar and return the
   * contract.
   */
  public static ConsultingService assemble(
      Patient patient,
      MedicalRecordRegistry registry,
      InterventionLedgerRegistry ledgerRegistry,
      List<Specialist> roster,
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
    return Generalist.builder()
        .specialists(roster)
        .access(access)
        .driftSpecialist(new DriftSpecialist(ledgerRegistry))
        .ledgerRegistry(ledgerRegistry)
        .build();
  }
}
