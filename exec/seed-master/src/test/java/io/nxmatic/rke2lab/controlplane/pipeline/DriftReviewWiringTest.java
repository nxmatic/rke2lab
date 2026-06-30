package io.nxmatic.rke2lab.controlplane.pipeline;

import io.nxmatic.rke2lab.controlplane.bdd.DriftReview;
import io.nxmatic.rke2lab.doctor.ExactRosterDoctor;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
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
    // No backend → the drift-at-reconstruction review returns without touching anything; must not
    // throw.
    final MedicalRecordRegistry registry = patient -> new MedicalRecord(patient, List.of());
    final ConsultingService doctor =
        ExactRosterDoctor.over(PATIENT, registry, intervention -> {}, List.of(), msg -> {});
    new DriftReview(null).reviewAtReconstruction(doctor);
    // No assertion beyond "did not throw"; the guard is the behaviour under test.
  }
}
