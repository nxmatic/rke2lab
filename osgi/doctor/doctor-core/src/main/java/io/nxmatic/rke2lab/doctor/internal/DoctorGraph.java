package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.port.DoctorConsultingService;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.List;
import java.util.function.Consumer;

/**
 * The single construction path for the doctor graph, shared by the production {@link Doctor} façade
 * (which prepends the standard roster) and {@link ExactRosterDoctor} (which takes the roster
 * verbatim). Package-private: only the two public factories in this package reach it, so the actors
 * it news up stay hidden behind the {@link DoctorConsultingService} it returns.
 */
public final class DoctorGraph {

  private DoctorGraph() {}

  /** Wire the registry + writer + the EXACT roster into the actors; return the contract. */
  public static DoctorConsultingService assemble(
      Patient patient,
      MedicalRecordRegistry registry,
      InterventionLedgerWriter ledgerWriter,
      List<Specialist> roster,
      Consumer<String> logger) {
    final DriftSpecialist driftSpecialist = new DriftSpecialist(ledgerWriter);
    return HealthSystem.admit(patient, registry, roster, driftSpecialist, logger).generalist();
  }
}
