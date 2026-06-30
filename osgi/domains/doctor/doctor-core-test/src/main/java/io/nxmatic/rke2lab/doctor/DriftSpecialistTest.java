package io.nxmatic.rke2lab.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.internal.*;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.ProblemReview;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.ResolutionPredicate;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.records.Visit;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
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
    final Map<String, Object> inferred = writer.payloadOf(0);
    assertEquals(Provenance.EXTERNAL_CHANGE_DETECTED.id(), inferred.get("provenance"));
    assertEquals(problem.toRef(), inferred.get("problem"));
    assertEquals(T2.toString(), inferred.get("when"));
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final List<Document> captured = new ArrayList<>();

    @Override
    public void append(Document intervention) {
      // Only the canonical intervention Document crosses the seam now.
      org.junit.jupiter.api.Assertions.assertEquals(
          Coordinate.INTERVENTION.slug(), intervention.coordinate());
      captured.add(intervention);
    }

    /** The flat output-map shape carried by the captured Document at {@code index}. */
    Map<String, Object> payloadOf(int index) {
      try {
        return MAPPER.readValue(captured.get(index).payload(), MAP);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
