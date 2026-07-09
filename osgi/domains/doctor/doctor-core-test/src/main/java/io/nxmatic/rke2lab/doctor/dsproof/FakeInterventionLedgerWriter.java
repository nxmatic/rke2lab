package io.nxmatic.rke2lab.doctor.dsproof;

import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import org.osgi.service.component.annotations.Component;

/**
 * The DS boot proof's ledger port: a no-op {@link InterventionLedgerWriter} {@code @Component} so
 * {@code DefaultHealthSystem}'s {@code @Reference} to the ledger is satisfied and the institution
 * activates. The proof exercises admission + consult, not drift persistence.
 */
@Component(service = InterventionLedgerWriter.class)
public final class FakeInterventionLedgerWriter implements InterventionLedgerWriter {

  @Override
  public void append(SeedEnvelope intervention) {
    // no-op: the DS proof does not assert persistence
  }
}
