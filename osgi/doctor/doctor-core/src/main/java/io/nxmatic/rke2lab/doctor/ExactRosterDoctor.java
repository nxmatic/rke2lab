package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.doctor.port.DoctorConsultingService;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.List;
import java.util.function.Consumer;

/**
 * A test-support factory that assembles a {@link DoctorConsultingService} over an EXACT roster —
 * the specialists passed are the only ones consulted, with NONE of the standard roster the
 * production {@link Doctor} façade prepends. It exists because the actors are package-private (the
 * sealed internal edge), so a host test cannot build the graph itself; it needs precise roster
 * control to assert a specific specialist's prescription (e.g. a network specialist's
 * CHECK_CONNECTIVITY) with no declining core specialist diluting the fan-out.
 *
 * <p>Not for production wiring — production goes through {@link Doctor#consultingService} so it
 * gets the standard roster. This is the one public seam that lets a host test drive the hidden
 * graph with a roster it fully controls.
 */
public final class ExactRosterDoctor {

  private ExactRosterDoctor() {}

  public static DoctorConsultingService over(
      Patient patient,
      MedicalRecordRegistry registry,
      InterventionLedgerWriter ledgerWriter,
      List<Specialist> exactRoster,
      Consumer<String> logger) {
    return DoctorGraph.assemble(patient, registry, ledgerWriter, exactRoster, logger);
  }
}
