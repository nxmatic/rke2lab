package io.nxmatic.rke2lab.doctor.dsproof;

import io.nxmatic.rke2lab.doctor.port.MedicalRecordJournal;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The DS boot proof's medical-record READ port: an empty {@link MedicalRecordJournal}
 * {@code @Component} so the real {@code JournalMedicalRecordRegistry} (which {@code @Reference}s
 * the journal) activates and publishes {@code MedicalRecordRegistry}, which in turn satisfies
 * {@code DefaultHealthSystem}. Yields no visit SeedEnvelopes for any patient — enough to admit and
 * consult. This proves the NEW graph (host journal → internal registry → institution), not the old
 * host-published registry.
 */
@Component(service = MedicalRecordJournal.class)
public final class FakeMedicalRecordJournal implements MedicalRecordJournal {

  @Override
  public List<SeedEnvelope> historyOf(Patient patient) {
    return List.of();
  }

  @Override
  public List<Patient> cohort(Patient current) {
    return List.of(current);
  }
}
