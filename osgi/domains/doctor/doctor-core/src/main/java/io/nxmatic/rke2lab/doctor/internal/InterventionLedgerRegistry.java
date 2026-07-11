package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.contract.Intervention;
import io.nxmatic.rke2lab.doctor.contract.InterventionLedger;

/**
 * The institution's standing access to its intervention ledger — the doctor-vocabulary twin of
 * {@link MedicalRecordRegistry}. The {@link Generalist} reads the current {@link
 * InterventionLedger} from it at drift-review time; the {@link DriftSpecialist} records an inferred
 * intervention into it. Held for the doctor's lifetime, so {@link #ledger} never throws: a fold
 * failure degrades to an empty ledger, never an exception the diagnosis path must defend against.
 *
 * <p>This is a PURE doctor concept — {@code InterventionLedger} in, {@code Intervention} out. The
 * neutral {@code Cellar} it is backed by lives ONLY in the frontier impl ({@code
 * CellarInterventionLedgerRegistry}); the core never names a cellar, exactly as the generalist
 * never names the host's stack. The register switch (gardening cellar → doctor ledger) happens at
 * that one frontier.
 */
public interface InterventionLedgerRegistry {

  /** The current intervention ledger, folded from the institution's store. Never throws. */
  InterventionLedger ledger();

  /** Record one intervention into the ledger (append-only). */
  void record(Intervention intervention);
}
