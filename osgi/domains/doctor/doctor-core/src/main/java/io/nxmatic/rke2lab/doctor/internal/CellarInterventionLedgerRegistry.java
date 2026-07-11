package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.contract.Intervention;
import io.nxmatic.rke2lab.doctor.contract.InterventionLedger;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The frontier that backs the doctor's {@link InterventionLedgerRegistry} with the neutral {@link
 * Cellar} — the intervention twin of {@link JournalMedicalRecordRegistry}. It is the ONE place the
 * register switches: the gardening {@code Cellar} (store/fetch a parcel) on one side, the doctor's
 * {@link InterventionLedger} / {@link Intervention} on the other. {@code fetch} the fixed ledger
 * parcel and fold via {@link InterventionLedgerReader}; {@code store} an intervention encoded by
 * {@link InterventionWriter}. The core ({@link Generalist}, {@link DriftSpecialist}) consumes only
 * this doctor-vocabulary registry and never names a cellar.
 */
@Component(service = InterventionLedgerRegistry.class)
public final class CellarInterventionLedgerRegistry implements InterventionLedgerRegistry {

  private final Cellar cellar;
  private final InterventionLedgerReader reader = new InterventionLedgerReader();

  @Activate
  public CellarInterventionLedgerRegistry(@Reference Cellar cellar) {
    this.cellar = cellar;
  }

  @Override
  public InterventionLedger ledger() {
    return reader.read(cellar.fetch(ParcelProjection.LEDGER));
  }

  @Override
  public void record(Intervention intervention) {
    cellar.store(ParcelProjection.LEDGER, InterventionWriter.of(intervention));
  }
}
