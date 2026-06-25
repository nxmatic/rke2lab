package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.doctor.port.DoctorConsultingService;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.pulumi.edge.InterventionLedgerLayout;
import io.nxmatic.rke2lab.pulumi.edge.InterventionLedgerSource;
import java.nio.file.Path;

/**
 * The host-driven follow-up after a patient is admitted: load the run's intervention ledger from
 * the Pulumi backend and let the doctor review every resolved problem (the drift specialist
 * persists any inferred external change through its own writer). Bound to the backend it reads, so
 * the timing — when, against which backend — is host knowledge held as an instance, not a static
 * gesture; a {@code null} backend makes the review a no-op (nothing to load or persist).
 */
public final class DriftReview {

  private final Path backendDir;

  public DriftReview(Path backendDir) {
    this.backendDir = backendDir;
  }

  /**
   * Symptom-independent: after the record is reconstructed for the run's patient, fold the ledger
   * over every open problem. A no-op when no {@code file://} backend is configured.
   */
  public void reviewAtReconstruction(DoctorConsultingService doctor) {
    if (backendDir == null) {
      return;
    }
    final MedicalRecord record = doctor.recordForCurrentPatient();
    final InterventionLedger ledger =
        new InterventionLedgerSource(backendDir, InterventionLedgerLayout.ledger()).load();
    doctor.reviewOpenProblems(record, ledger);
  }
}
