package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.HealthSystem;
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
 * <p>The EHR ({@link MedicalRecordRegistry}) and the intervention ledger ({@link
 * InterventionLedgerRegistry}) are both OSGi {@code @Component}s ({@code
 * JournalMedicalRecordRegistry}, {@code CellarInterventionLedgerRegistry}) that fold the host's
 * opaque {@code Cellar} SeedEnvelopes into records/ledger inside the bundle. The host publishes ONE
 * neutral {@link io.nxmatic.rke2lab.seed.broker.port.Cellar}; until it does, the two frontier
 * registries stay unsatisfied (so this institution does not activate); once published, SCR
 * activates the registries, then this institution, and the host admits the patient across the seam.
 * No record or ledger crosses back — {@link ConsultingService#reviewDrift()} rebuilds them
 * OSGi-side. The core never names a cellar: the register switch (gardening Cellar → doctor
 * MedicalRecord/InterventionLedger) lives only in those two frontier registries.
 */
@Component(service = HealthSystem.class)
public final class DefaultHealthSystem implements HealthSystem {

  private final List<Specialist> specialists;
  private final MedicalRecordRegistry registry;
  private final InterventionLedgerRegistry ledgerRegistry;

  @Activate
  public DefaultHealthSystem(
      @Reference(
              target = ClinicianProperties.TARGET_DOMAIN_DIAGNOSTICIANS,
              cardinality = ReferenceCardinality.MULTIPLE)
          List<Specialist> specialists,
      @Reference MedicalRecordRegistry registry,
      @Reference InterventionLedgerRegistry ledgerRegistry) {
    this.specialists = List.copyOf(specialists);
    this.registry = registry;
    this.ledgerRegistry = ledgerRegistry;
  }

  @Override
  public ConsultingService admit(Patient patient) {
    return ConsultationDag.assemble(patient, registry, ledgerRegistry, specialists, msg -> {});
  }
}
