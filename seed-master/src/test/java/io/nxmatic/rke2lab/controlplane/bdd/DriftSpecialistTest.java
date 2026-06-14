package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    final CapturingWriter writer = new CapturingWriter();
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

    final CapturingWriter writer = new CapturingWriter();
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

    final CapturingWriter writer = new CapturingWriter();
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

    final CapturingWriter writer = new CapturingWriter();
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

  private static final class CapturingWriter implements InterventionLedgerWriter {
    private final List<Intervention> captured = new ArrayList<>();

    @Override
    public void append(Intervention intervention) {
      captured.add(intervention);
    }
  }
}
