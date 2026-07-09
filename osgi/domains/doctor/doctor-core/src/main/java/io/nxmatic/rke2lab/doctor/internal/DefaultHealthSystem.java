package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.HealthSystem;
import io.nxmatic.rke2lab.doctor.port.InterventionJournal;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordJournal;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.spi.ClinicianProperties;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import java.util.List;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * The institution — the {@link HealthSystem} the host crosses to obtain a doctor, published OSGi
 * service-side so the diagnostic model and its specialists never reach the host (only this seam and
 * the returned {@link ConsultingService} do). It HOLDS its infrastructure (the EHR, the
 * intervention ledger) and EMPLOYS its clinicians, and {@link #admit admits} a patient: mints the
 * run's grants, binds a credentialed {@link ClinicalAccess}, and employs the {@link Generalist}.
 *
 * <p>The diagnosing roster arrives by Declarative Services: a {@code @Reference} over the
 * domain-level diagnosticians ({@link ClinicianProperties#TARGET_DOMAIN_DIAGNOSTICIANS}). The tier
 * filter is load-bearing — it collects only the top domain specialists, never a composite
 * specialist's internal sub-specialists ({@link ClinicianProperties#TIER_SUB}), so a specialist
 * that is itself a coordinator cannot collapse the roster.
 *
 * <p>The EHR ({@link MedicalRecordRegistry}) is now an OSGi {@code @Component} ({@code
 * JournalMedicalRecordRegistry}) that folds the host {@link MedicalRecordJournal}'s opaque
 * Documents into records inside the bundle; the ledger writer ({@link InterventionLedgerWriter})
 * and the read journals ({@link MedicalRecordJournal}, {@link InterventionJournal}) are
 * host-published ports. Until the host publishes the two journals + the writer they stay
 * unsatisfied (so the internal registry and this institution do not activate); once published, SCR
 * activates the registry, then this institution, and the host admits the patient across the seam.
 * No record or ledger crosses back — {@link ConsultingService#reviewDrift()} rebuilds them
 * OSGi-side.
 */
@Component(service = HealthSystem.class)
public final class DefaultHealthSystem implements HealthSystem {

  private final List<Specialist> specialists;
  private final MedicalRecordRegistry registry;
  private final InterventionLedgerWriter ledgerWriter;
  private final InterventionJournal interventionJournal;

  @Activate
  public DefaultHealthSystem(
      @Reference(
              target = ClinicianProperties.TARGET_DOMAIN_DIAGNOSTICIANS,
              cardinality = ReferenceCardinality.MULTIPLE)
          List<Specialist> specialists,
      @Reference MedicalRecordRegistry registry,
      @Reference InterventionLedgerWriter ledgerWriter,
      @Reference InterventionJournal interventionJournal) {
    this.specialists = List.copyOf(specialists);
    this.registry = registry;
    this.ledgerWriter = ledgerWriter;
    this.interventionJournal = interventionJournal;
  }

  @Override
  public ConsultingService admit(Patient patient) {
    return ConsultationDag.assemble(
        patient, registry, ledgerWriter, interventionJournal, specialists, msg -> {});
  }
}
