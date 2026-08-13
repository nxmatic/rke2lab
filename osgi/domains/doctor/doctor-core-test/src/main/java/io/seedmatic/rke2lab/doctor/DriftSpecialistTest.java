package io.seedmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.doctor.contract.Checkpoint;
import io.seedmatic.rke2lab.doctor.contract.Expectation;
import io.seedmatic.rke2lab.doctor.contract.Intervention;
import io.seedmatic.rke2lab.doctor.contract.InterventionLedger;
import io.seedmatic.rke2lab.doctor.contract.ProblemRef;
import io.seedmatic.rke2lab.doctor.contract.ProblemReview;
import io.seedmatic.rke2lab.doctor.contract.Provenance;
import io.seedmatic.rke2lab.doctor.contract.ReferralReply;
import io.seedmatic.rke2lab.doctor.contract.RemediationProgramRef;
import io.seedmatic.rke2lab.doctor.contract.ResolutionPredicate;
import io.seedmatic.rke2lab.doctor.contract.Symptom;
import io.seedmatic.rke2lab.doctor.contract.Visit;
import io.seedmatic.rke2lab.doctor.internal.DriftSpecialist;
import io.seedmatic.rke2lab.doctor.internal.InterventionLedgerRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DriftSpecialistTest {

  private static final Instant T0 = Instant.parse("2026-06-14T09:00:00Z");
  private static final Instant T1 = Instant.parse("2026-06-14T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-06-14T11:00:00Z");

  @Test
  void declaredOperatorInterventionExplainsResolutionWithoutPrescriptionOrAppend() {
    final ProblemRef problem =
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED);
    final Intervention declared =
        new Intervention(
            Provenance.OPERATOR_MANUAL, T1, "nft delete ...", problem, Optional.empty(), Map.of());
    final ProblemReview review =
        reviewFor(problem, new InterventionLedger(List.of(declared)), 0, 1);

    final CapturingLedger writer = new CapturingLedger();
    final ReferralReply reply = new DriftSpecialist(writer).review(review);

    assertFalse(reply.hasPrescription());
    assertTrue(reply.prescription().isEmpty());
    assertTrue(reply.assessment().summary().contains("declared"));
    assertEquals("drift/confounded-declared/v1", reply.assessment().schemaRef().id());
    assertEquals("nft delete ...", reply.assessment().payload().get("declaredWhat"));
    assertTrue(writer.captured.isEmpty());
  }

  @Test
  void noDeclarationInfersExternalChangeAndRecordsIt() {
    final ProblemRef problem =
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED);
    final ProblemReview review = reviewFor(problem, InterventionLedger.empty(), 0, 1);

    final CapturingLedger writer = new CapturingLedger();
    final ReferralReply reply = new DriftSpecialist(writer).review(review);

    assertTrue(reply.prescription().isEmpty());
    assertTrue(reply.assessment().summary().contains("inferred"));
    assertEquals("drift/confounded-inferred/v1", reply.assessment().schemaRef().id());

    assertEquals(1, writer.captured.size());
    final Intervention inferred = writer.captured.get(0);
    assertEquals(Provenance.EXTERNAL_CHANGE_DETECTED, inferred.provenance());
    assertEquals(problem, inferred.problem());
    assertEquals(T2, inferred.when());
  }

  @Test
  void priorInferenceIsNotRecordedTwice() {
    final ProblemRef problem =
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED);
    final Intervention priorInference =
        new Intervention(
            Provenance.EXTERNAL_CHANGE_DETECTED,
            T1,
            "unexplained resolution ...",
            problem,
            Optional.empty(),
            Map.of());
    final ProblemReview review =
        reviewFor(problem, new InterventionLedger(List.of(priorInference)), 0, 1);

    final CapturingLedger writer = new CapturingLedger();
    final ReferralReply reply = new DriftSpecialist(writer).review(review);

    assertEquals("drift/confounded-inferred/v1", reply.assessment().schemaRef().id());
    assertTrue(reply.prescription().isEmpty());
    assertTrue(writer.captured.isEmpty());
  }

  @Test
  void checkpointOnlyDeclarationExplainsSymptomSpecificResolution() {
    final ProblemRef declaredProblem = ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER);
    final Intervention declared =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            T1,
            "systemctl restart ...",
            declaredProblem,
            Optional.empty(),
            Map.of());
    final ProblemRef reviewProblem =
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED);
    final ProblemReview review =
        reviewFor(reviewProblem, new InterventionLedger(List.of(declared)), 0, 1);

    final CapturingLedger writer = new CapturingLedger();
    final ReferralReply reply = new DriftSpecialist(writer).review(review);

    assertTrue(reply.assessment().summary().contains("declared"));
    assertEquals("drift/confounded-declared/v1", reply.assessment().schemaRef().id());
    assertTrue(writer.captured.isEmpty());
  }

  private static ProblemReview reviewFor(
      ProblemRef problem, InterventionLedger ledger, int priorVersion, int nextVersion) {
    final Expectation expectation =
        new Expectation(
            problem,
            RemediationProgramRef.RESTART_UNIT,
            new ResolutionPredicate(problem.symptom().orElse(Symptom.CONNECTION_REFUSED)),
            T0);
    final Visit prior = new Visit(priorVersion, T0, List.of(), List.of());
    final Visit next = new Visit(nextVersion, T2, List.of(), List.of());
    return new ProblemReview(problem, expectation, prior, next, ledger);
  }

  /**
   * A capturing {@link InterventionLedgerRegistry} — the mock the test injects into the drift
   * specialist. The core records a typed {@link Intervention} (the register switch to a
   * SeedEnvelope happens at the Cellar frontier, not here), so the test asserts on the record's own
   * fields.
   */
  private static final class CapturingLedger implements InterventionLedgerRegistry {
    private final List<Intervention> captured = new ArrayList<>();

    @Override
    public InterventionLedger ledger() {
      return new InterventionLedger(List.copyOf(captured));
    }

    @Override
    public void record(Intervention intervention) {
      captured.add(intervention);
    }
  }
}
