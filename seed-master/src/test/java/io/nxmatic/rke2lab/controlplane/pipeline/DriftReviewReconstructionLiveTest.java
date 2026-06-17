package io.nxmatic.rke2lab.controlplane.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.controlplane.bdd.Checkpoint;
import io.nxmatic.rke2lab.controlplane.bdd.DriftSpecialist;
import io.nxmatic.rke2lab.controlplane.bdd.Expectation;
import io.nxmatic.rke2lab.controlplane.bdd.GrpcChannelNoiseCapture;
import io.nxmatic.rke2lab.controlplane.bdd.HealthSystem;
import io.nxmatic.rke2lab.controlplane.bdd.Intervention;
import io.nxmatic.rke2lab.controlplane.bdd.InterventionLedger;
import io.nxmatic.rke2lab.controlplane.bdd.InterventionLedgerSource;
import io.nxmatic.rke2lab.controlplane.bdd.MedicalRecord;
import io.nxmatic.rke2lab.controlplane.bdd.MedicalRecordRegistry;
import io.nxmatic.rke2lab.controlplane.bdd.Patient;
import io.nxmatic.rke2lab.controlplane.bdd.ProblemRef;
import io.nxmatic.rke2lab.controlplane.bdd.Provenance;
import io.nxmatic.rke2lab.controlplane.bdd.PulumiInterventionLedgerWriter;
import io.nxmatic.rke2lab.controlplane.bdd.RemediationProgramRef;
import io.nxmatic.rke2lab.controlplane.bdd.ResolutionPredicate;
import io.nxmatic.rke2lab.controlplane.bdd.Symptom;
import io.nxmatic.rke2lab.controlplane.bdd.Visit;
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

    // The real ledger writer points at the @TempDir backend; the source reads it back.
    final DriftSpecialist drift =
        new DriftSpecialist(new PulumiInterventionLedgerWriter(backendDir));
    final HealthSystem hs = HealthSystem.admit(PATIENT, registry, List.of(), drift, msg -> {});

    BootstrapPipeline.reviewDriftAtReconstruction(hs, backendDir);

    final InterventionLedger ledger = new InterventionLedgerSource(backendDir).load();
    assertEquals(1, ledger.interventions().size(), "one inferred external change persisted");
    final Intervention inferred = ledger.interventions().get(0);
    assertEquals(Provenance.EXTERNAL_CHANGE_DETECTED, inferred.provenance());
    assertEquals(problem, inferred.problem());
  }
}
