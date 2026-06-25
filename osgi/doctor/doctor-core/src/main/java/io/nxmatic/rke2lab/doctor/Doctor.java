package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.doctor.port.DoctorConsultingService;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The assembly façade — the one public way to get a {@link DoctorConsultingService} without
 * touching the hidden actors. The host supplies only port-typed impls (the registry, the ledger
 * writer, any extra specialists it wants to inject, the patient); the core wires the rest
 * internally — the standard roster ({@code DbusTcpSpecialist} + {@code NetworkSpecialist} + {@code
 * ClusterSpecialist}), the {@code DriftSpecialist}, the {@code HealthSystem} that mints the grant
 * policy + access, and the {@code Generalist} that the service resolves to. Keeping construction
 * here is what lets the actors stay package-private: no module outside this package ever names
 * them.
 */
public final class Doctor {

  private Doctor() {}

  /**
   * Assemble the doctor for a run and return its internal-edge contract. {@code hostSpecialists}
   * are extra specialists the host injects (e.g. fakes in a test); the standard roster — all
   * parameter-free now that the dbus-tcp specialist reads its endpoint off the observation — is
   * added by the core.
   */
  public static DoctorConsultingService consultingService(
      Patient patient,
      MedicalRecordRegistry registry,
      InterventionLedgerWriter ledgerWriter,
      List<Specialist> hostSpecialists,
      Consumer<String> logger) {
    final List<Specialist> roster = new ArrayList<>(hostSpecialists);
    roster.add(new DbusTcpSpecialist());
    roster.add(new NetworkSpecialist());
    roster.add(new ClusterSpecialist());
    return DoctorGraph.assemble(patient, registry, ledgerWriter, roster, logger);
  }
}
