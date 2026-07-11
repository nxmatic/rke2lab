package io.nxmatic.rke2lab.doctor;

import io.nxmatic.rke2lab.doctor.contract.Assessment;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.MedicalRecord;
import io.nxmatic.rke2lab.doctor.contract.Patient;
import io.nxmatic.rke2lab.doctor.contract.Prescription;
import io.nxmatic.rke2lab.doctor.contract.Referral;
import io.nxmatic.rke2lab.doctor.contract.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.contract.SchemaRef;
import io.nxmatic.rke2lab.doctor.contract.Specialty;
import io.nxmatic.rke2lab.doctor.internal.ConsultationDag;
import io.nxmatic.rke2lab.doctor.internal.InterventionLedgerRegistry;
import io.nxmatic.rke2lab.doctor.internal.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
      InterventionLedgerRegistry ledgerRegistry,
      List<Specialist> exactRoster,
      Consumer<String> logger) {
    return ConsultationDag.assemble(patient, registry, ledgerRegistry, exactRoster, logger);
  }

  /**
   * An empty intervention-ledger registry — no ledger to fold, records nowhere (the test degrade).
   */
  private static InterventionLedgerRegistry emptyLedger() {
    return new InterventionLedgerRegistry() {
      @Override
      public io.nxmatic.rke2lab.doctor.contract.InterventionLedger ledger() {
        return io.nxmatic.rke2lab.doctor.contract.InterventionLedger.empty();
      }

      @Override
      public void record(io.nxmatic.rke2lab.doctor.contract.Intervention intervention) {}
    };
  }

  /**
   * A ready doctor over an empty roster — no specialist replies. The host obtains it without naming
   * any {@code doctor.records}/{@code spi}/{@code internal} type; only the {@link
   * ConsultingService} seam and the neutral {@link Patient} cross.
   */
  public static ConsultingService readyGeneralist(Patient patient) {
    return over(patient, p -> new MedicalRecord(p, List.of()), emptyLedger(), List.of(), msg -> {});
  }

  /**
   * A doctor over a single network specialist: a TIMEOUT symptom routed to NETWORK yields a
   * CHECK_CONNECTIVITY prescription. Same host-pure seam contract as {@link
   * #readyGeneralist(Patient)}.
   */
  public static ConsultingService networkGeneralist(Patient patient) {
    return over(
        patient,
        p -> new MedicalRecord(p, List.of()),
        emptyLedger(),
        List.of(new FakeNetworkSpecialist()),
        msg -> {});
  }

  /** A stand-in network specialist so a TIMEOUT (routed to NETWORK) yields a prescription. */
  private static final class FakeNetworkSpecialist implements Specialist {
    @Override
    public Specialty domain() {
      return Specialty.NETWORK;
    }

    @Override
    public Assessment assess(Referral referral) {
      return Assessment.of(
          SchemaRef.of("network/check-connectivity/v1"),
          Map.of("symptom", referral.symptom().id()),
          "the API endpoint may be unreachable — verify network connectivity first");
    }

    @Override
    public Optional<Prescription> prescribe(Referral referral, Assessment assessment) {
      return Optional.of(
          Prescription.of(
              RemediationProgramRef.CHECK_CONNECTIVITY,
              Map.of("symptom", referral.symptom().id()),
              "check connectivity to the API endpoint"));
    }
  }
}
