package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.port.DoctorConsultingService;
import io.nxmatic.rke2lab.doctor.port.HealthSystem;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
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
 * the returned {@link DoctorConsultingService} do). It HOLDS its infrastructure (the EHR, the
 * intervention ledger) and EMPLOYS its clinicians, and {@link #admit admits} a patient: mints the
 * run's grants, binds a credentialed {@link ClinicalAccess}, and employs the {@link Generalist}.
 *
 * <p>The diagnosing roster arrives by Declarative Services: a {@code @Reference} over the
 * domain-level diagnosticians ({@link ClinicianProperties#TARGET_DOMAIN_DIAGNOSTICIANS}). The tier
 * filter is load-bearing — it collects only the top domain specialists, never a composite
 * specialist's internal sub-specialists ({@link ClinicianProperties#TIER_SUB}), so a specialist
 * that is itself a coordinator cannot collapse the roster.
 *
 * <p>The EHR ({@link MedicalRecordRegistry}) and ledger ({@link InterventionLedgerWriter}) are
 * institutional infrastructure, referenced as ports — not handed over at the bedside. Until the
 * host publishes them as services they stay unsatisfied and this component does not activate; the
 * flat assembly path ({@link io.nxmatic.rke2lab.doctor.Doctor}) still serves in the meantime.
 */
@Component(service = HealthSystem.class)
public final class DefaultHealthSystem implements HealthSystem {

  private final List<Specialist> specialists;
  private final MedicalRecordRegistry registry;
  private final InterventionLedgerWriter ledgerWriter;

  @Activate
  public DefaultHealthSystem(
      @Reference(
              target = ClinicianProperties.TARGET_DOMAIN_DIAGNOSTICIANS,
              cardinality = ReferenceCardinality.MULTIPLE)
          List<Specialist> specialists,
      @Reference MedicalRecordRegistry registry,
      @Reference InterventionLedgerWriter ledgerWriter) {
    this.specialists = List.copyOf(specialists);
    this.registry = registry;
    this.ledgerWriter = ledgerWriter;
  }

  @Override
  public DoctorConsultingService admit(Patient patient) {
    return DoctorGraph.assemble(patient, registry, ledgerWriter, specialists, msg -> {});
  }
}
