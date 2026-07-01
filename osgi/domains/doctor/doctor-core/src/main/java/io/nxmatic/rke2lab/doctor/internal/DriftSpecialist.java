package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.ProblemReview;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.domain.annotations.Transitional;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The follow-up specialist held OUTSIDE the acute roster: it never treats a symptom, so it is not
 * consulted on the acute {@code diagnose} seam. Instead, at a later visit, it reviews a problem
 * whose prescription's {@link Expectation} now holds and answers the only question that makes
 * efficacy honest — WHO actually fixed it. Since no engine administers fixes today, a resolution is
 * either a declared operator intervention already in the ledger, or an unexplained external change
 * the specialist must infer and record into its own ledger.
 *
 * <p>Both branches return an assessment-only {@link ReferralReply} with no {@link Prescription}:
 * the specialist explains a resolution, it does not propose a treatment. The reply is built with
 * {@link ReferralReply#reconstructed} — there is no synthetic live {@link Referral}, because that
 * transient back-ref belongs only to the acute {@code diagnose} round-trip, not to this follow-up.
 *
 * <p>The inference is idempotent across reruns: a prior inferred external change already in the
 * window suppresses re-recording, so one real change is never recorded N times over N runs.
 *
 * <p>The settled design reframes this as the médecin-conseil — a pure-reader efficacy analyst that
 * produces an EfficacyReport and INFERS/WRITES nothing (the external-change inference is dropped).
 */
@Transitional(
    to = "médecin-conseil (efficacy analyst)",
    spec = "efficacy-and-medecin-conseil-design.adoc")
public final class DriftSpecialist {

  private final InterventionLedgerWriter writer;

  public DriftSpecialist(InterventionLedgerWriter writer) {
    this.writer = Objects.requireNonNull(writer, "writer");
  }

  public ReferralReply review(ProblemReview review) {
    final List<Intervention> candidates =
        review.ledger().between(review.priorVisit().when(), review.nextVisit().when()).stream()
            .filter(i -> i.problem().explains(review.problem()))
            .filter(i -> i.provenance() != Provenance.PULUMI_ENGINE)
            .toList();

    final Optional<Intervention> declared =
        candidates.stream().filter(i -> i.provenance() == Provenance.OPERATOR_MANUAL).findFirst();

    if (declared.isPresent()) {
      final Intervention m = declared.get();
      final Assessment assessment =
          new Assessment(
              SchemaRef.of("drift/confounded-declared/v1"),
              Map.of("declaredWhat", m.what()),
              "resolved by a declared operator intervention; the prescription is confounded");
      return ReferralReply.reconstructed(assessment, Optional.empty());
    }

    // Idempotent inference: if a prior run already inferred an external change for this problem in
    // this window, the resolution is still confounded but the fact is already recorded — do not
    // append it again (the specialist must not record one real change N times across N runs).
    final boolean alreadyInferred =
        candidates.stream().anyMatch(i -> i.provenance() == Provenance.EXTERNAL_CHANGE_DETECTED);
    if (alreadyInferred) {
      return confoundedInferred(review);
    }

    writer.append(
        InterventionDocuments.of(
            new Intervention(
                Provenance.EXTERNAL_CHANGE_DETECTED,
                review.nextVisit().when(),
                "unexplained resolution of "
                    + review.problem().toRef()
                    + " between v"
                    + review.priorVisit().version()
                    + " and v"
                    + review.nextVisit().version(),
                review.problem(),
                Optional.empty(),
                Map.of(
                    "windowFrom", review.priorVisit().when().toString(),
                    "windowTo", review.nextVisit().when().toString()))));
    return confoundedInferred(review);
  }

  /**
   * The confounded-inferred letter — returned both when this run infers a fresh external change and
   * when a prior run already did (idempotent path). Same assessment either way; only the append
   * differs.
   */
  private static ReferralReply confoundedInferred(ProblemReview review) {
    final Assessment assessment =
        new Assessment(
            SchemaRef.of("drift/confounded-inferred/v1"),
            Map.of(
                "windowFrom", review.priorVisit().when().toString(),
                "windowTo", review.nextVisit().when().toString()),
            "resolved with no administered prescription and no declaration — external change"
                + " inferred; the prescription is confounded");
    return ReferralReply.reconstructed(assessment, Optional.empty());
  }
}
