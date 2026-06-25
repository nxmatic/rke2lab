package io.nxmatic.rke2lab.controlplane.bdd;

import com.pulumi.deployment.Deployment;
import io.nxmatic.rke2lab.doctor.Doctor;
import io.nxmatic.rke2lab.doctor.port.DoctorConsultingService;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.port.Specialist;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.pulumi.edge.InterventionLedgerLayout;
import io.nxmatic.rke2lab.pulumi.edge.InterventionLedgerSource;
import io.nxmatic.rke2lab.pulumi.edge.LiveMedicalRecordRegistry;
import io.nxmatic.rke2lab.pulumi.edge.PulumiInterventionLedgerWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Host-side assembly of the doctor for a run: it reads env/Pulumi, picks the host impls of the
 * doctor ports (the live registry, the Pulumi ledger writer or a no-op, the patient under care),
 * and hands them to the core's {@link Doctor} façade — which returns the {@link
 * DoctorConsultingService} the pipeline consults. The hidden actors are never named here; only the
 * façade and the ports are.
 *
 * <p>Construction lives on the host (not in the port) so the model never couples to host config —
 * the assembly is the seam where host knowledge meets the pure model. The specialists are all
 * parameter-free now (they read their facts off the observation), so none is built from config
 * here.
 */
public final class DoctorAssembly {

  private DoctorAssembly() {}

  /**
   * The production entry: built once at the readiness transition. The patient is this Pulumi
   * stack's org/project/stack under the engine, a placeholder otherwise; the registry degrades to
   * an empty record when no {@code file://} backend is configured, and the drift writer is a no-op
   * then (inference computed, never stored).
   */
  public static DoctorConsultingService assemble(boolean pulumiMode, Consumer<String> logger) {
    final LiveMedicalRecordRegistry registry = LiveMedicalRecordRegistry.fromEnvironment(logger);
    final Path backendDir = registry.backendDir();
    final InterventionLedgerWriter writer =
        backendDir != null ? new PulumiInterventionLedgerWriter(backendDir) : intervention -> {};
    return assembleWith(
        currentPatient(pulumiMode), registry, writer, List.of(), backendDir, logger);
  }

  /**
   * The injectable seam: assemble from explicit ports and run the drift-at-reconstruction review.
   * The host path and the drift tests both enter here. {@code hostSpecialists} are extra
   * specialists a caller injects (e.g. fakes in a test); the standard roster is added by the core
   * façade.
   */
  public static DoctorConsultingService assembleWith(
      Patient patient,
      MedicalRecordRegistry registry,
      InterventionLedgerWriter ledgerWriter,
      List<Specialist> hostSpecialists,
      Path backendDir,
      Consumer<String> logger) {
    final DoctorConsultingService doctor =
        Doctor.consultingService(patient, registry, ledgerWriter, hostSpecialists, logger);
    reviewDriftAtReconstruction(doctor, backendDir);
    return doctor;
  }

  /**
   * The symptom-independent follow-up: after the record is reconstructed for the run's patient,
   * load the intervention ledger and let the doctor review every resolved problem (the drift
   * specialist persists any inferred external change through its own writer). A no-op when no
   * {@code file://} backend is configured (nothing to load or persist).
   */
  private static void reviewDriftAtReconstruction(DoctorConsultingService doctor, Path backendDir) {
    if (backendDir == null) {
      return;
    }
    final MedicalRecord record = doctor.recordForCurrentPatient();
    final InterventionLedger ledger =
        new InterventionLedgerSource(backendDir, InterventionLedgerLayout.ledger()).load();
    doctor.reviewOpenProblems(record, ledger);
  }

  private static Patient currentPatient(boolean pulumiMode) {
    final Patient placeholder = new Patient("organization", "rke2lab", "standalone");
    if (!pulumiMode) {
      return placeholder;
    }
    try {
      final Deployment deployment = Deployment.getInstance();
      return new Patient(
          deployment.getOrganizationName(), deployment.getProjectName(), deployment.getStackName());
    } catch (RuntimeException noEngine) {
      return placeholder;
    }
  }
}
