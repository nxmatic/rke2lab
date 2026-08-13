package io.seedmatic.rke2lab.doctor.internal;

import io.seedmatic.rke2lab.doctor.contract.Intervention;
import io.seedmatic.rke2lab.doctor.contract.InterventionLedger;

/**
 * The no-backend degrade of an {@link InterventionLedgerRegistry}: an empty ledger, records
 * nowhere. Used when the doctor is assembled with no ledger registry wired — the drift inference is
 * still computed but not persisted, coherent with the medical-record registry's no-backend degrade.
 */
enum NoOpInterventionLedgerRegistry implements InterventionLedgerRegistry {
  INSTANCE;

  @Override
  public InterventionLedger ledger() {
    return InterventionLedger.empty();
  }

  @Override
  public void record(Intervention intervention) {
    // no backend to record into
  }
}
