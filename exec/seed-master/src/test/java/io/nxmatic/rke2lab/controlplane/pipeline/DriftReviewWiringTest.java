package io.nxmatic.rke2lab.controlplane.pipeline;

import io.nxmatic.rke2lab.doctor.DriftSpecialist;
import io.nxmatic.rke2lab.doctor.HealthSystem;
import io.nxmatic.rke2lab.doctor.MedicalRecord;
import io.nxmatic.rke2lab.doctor.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.Patient;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast guard checks of the drift-review wiring. The deploying case (which drives a real Pulumi
 * inline {@code up()} through {@code PulumiInterventionLedgerWriter}) lives in {@code @Tag("host")}
 * {@code DriftReviewReconstructionLiveTest}, so it is excluded from the default test run.
 */
class DriftReviewWiringTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  @Test
  void nullBackendIsANoOp() {
    // No backend → reviewDriftAtReconstruction returns without touching anything; must not throw.
    final MedicalRecordRegistry registry = patient -> new MedicalRecord(patient, List.of());
    final HealthSystem hs =
        HealthSystem.admit(
            PATIENT, registry, List.of(), new DriftSpecialist(intervention -> {}), msg -> {});
    BootstrapPipeline.reviewDriftAtReconstruction(hs, null);
    // No assertion beyond "did not throw"; the guard is the behaviour under test.
  }
}
