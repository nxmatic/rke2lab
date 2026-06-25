package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.port.DoctorConsultingService;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.ClinicianId;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.List;
import java.util.function.Consumer;

/**
 * The single construction path for the doctor graph: admit the patient (mint this run's self +
 * cohort grants), bind a credentialed {@link ClinicalAccess}, and employ the {@link Generalist}
 * over the roster + the run's {@link DriftSpecialist}. Shared by the OSGi {@link
 * io.nxmatic.rke2lab.doctor.internal.DefaultHealthSystem} (the institution, specialists by DS) and
 * the flat {@link io.nxmatic.rke2lab.doctor.Doctor} / {@link
 * io.nxmatic.rke2lab.doctor.ExactRosterDoctor} factories — so admission lives in exactly one place.
 * Package-private actors stay hidden behind the {@link DoctorConsultingService} it returns.
 */
public final class DoctorGraph {

  private DoctorGraph() {}

  /** Admit the patient over the EXACT roster + ledger writer and return the consulting contract. */
  public static DoctorConsultingService assemble(
      Patient patient,
      MedicalRecordRegistry registry,
      InterventionLedgerWriter ledgerWriter,
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
        .driftSpecialist(new DriftSpecialist(ledgerWriter))
        .build();
  }
}
