package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.doctor.internal.*;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.HealthSystem;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.util.List;
import java.util.function.Consumer;

/**
 * A test-support factory that assembles a {@link ConsultingService} over an EXACT roster — the
 * specialists passed are the only ones consulted. It exists because the actors are package-private
 * (the sealed internal edge), so a host test cannot build the graph itself; it needs precise roster
 * control to assert a specific specialist's prescription (e.g. a network specialist's
 * CHECK_CONNECTIVITY) with no declining specialist diluting the fan-out.
 *
 * <p>Not for production wiring — production admits the patient through the OSGi {@link
 * HealthSystem} ({@code DefaultHealthSystem}), whose roster arrives by Declarative Services. This
 * is the one public seam that lets a host test drive the hidden graph with a roster it fully
 * controls.
 */
public final class ExactRosterDoctor {

  private ExactRosterDoctor() {}

  public static ConsultingService over(
      Patient patient,
      MedicalRecordRegistry registry,
      InterventionLedgerWriter ledgerWriter,
      List<Specialist> exactRoster,
      Consumer<String> logger) {
    return ConsultationDag.assemble(patient, registry, ledgerWriter, exactRoster, logger);
  }
}
