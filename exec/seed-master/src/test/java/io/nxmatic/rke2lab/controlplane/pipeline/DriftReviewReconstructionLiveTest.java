package io.nxmatic.rke2lab.controlplane.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.controlplane.bdd.DriftReview;
import io.nxmatic.rke2lab.doctor.ExactRosterDoctor;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordRegistry;
import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.ResolutionPredicate;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.records.Visit;
import io.nxmatic.rke2lab.pulumi.edge.InterventionLedgerSource;
import io.nxmatic.rke2lab.pulumi.edge.PulumiInterventionLedgerWriter;
import io.nxmatic.rke2lab.pulumi.edge.testkit.GrpcChannelNoiseCapture;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deploying drift-review case: the drift specialist infers an external change and persists it
 * through a real {@code PulumiInterventionLedgerWriter} ({@code up()} inline). Tagged {@code host}
 * + {@code live} (excluded from the default run) and registers {@link GrpcChannelNoiseCapture} for
 * the benign gRPC channel noise. The fast no-op guard stays in {@code DriftReviewWiringTest}.
 */
@Tag("host")
@Tag("live")
final class DriftReviewReconstructionLiveTest {

  @RegisterExtension
  static final GrpcChannelNoiseCapture GRPC_NOISE = new GrpcChannelNoiseCapture();

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  @Test
  void reconstructionReviewPersistsAnInferredExternalChange(@TempDir Path backendDir) {
    // A two-visit record: v0 raised CONNECTION_REFUSED at systemd-adapter with a RESTART_UNIT
    // expectation; v1 is clean (the symptom resolved). No operator declaration exists, so the drift
    // specialist must INFER an external change and persist it. The fold is over expectations, so v0
    // needs no reports — only the expectation.
    final ProblemRef problem =
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED);
    final Expectation expectation =
        new Expectation(
            problem,
            RemediationProgramRef.RESTART_UNIT,
            new ResolutionPredicate(Symptom.CONNECTION_REFUSED),
            Instant.ofEpochSecond(1));
    final Visit v0 = new Visit(0, Instant.ofEpochSecond(1), List.of(), List.of(expectation));
    final Visit v1 = new Visit(1, Instant.ofEpochSecond(2), List.of(), List.of());
    final MedicalRecord seeded = new MedicalRecord(PATIENT, List.of(v0, v1));

    // A stub registry returns the seeded record for the patient (the reconstruction stand-in).
    final MedicalRecordRegistry registry = patient -> seeded;

    // The real ledger writer points at the @TempDir backend; the source reads it back. The doctor
    // is built over the exact (empty) roster — the run's drift specialist comes from the graph —
    // and
    // the host-driven review folds the ledger over the reconstructed record.
    final ConsultingService doctor =
        ExactRosterDoctor.over(
            PATIENT,
            registry,
            new PulumiInterventionLedgerWriter(backendDir),
            List.of(),
            msg -> {});
    new DriftReview(backendDir).reviewAtReconstruction(doctor);

    final InterventionLedger ledger = new InterventionLedgerSource(backendDir).load();
    assertEquals(1, ledger.interventions().size(), "one inferred external change persisted");
    final Intervention inferred = ledger.interventions().get(0);
    assertEquals(Provenance.EXTERNAL_CHANGE_DETECTED, inferred.provenance());
    assertEquals(problem, inferred.problem());
  }
}
