package io.nxmatic.rke2lab.doctor.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;
import java.util.Optional;

/**
 * The RESPONSE a specialist returns in the referral round-trip. It carries the transient back-ref
 * to the {@link Referral} (present on the LIVE round-trip, EMPTY on read-back from persistence —
 * honest modeling, not contract weakening), ALWAYS an {@link Assessment} (the "why" survives even
 * when the specialist has nothing to offer), and OPTIONALLY a {@link Prescription} (the action,
 * when one is offered). A "nothing to offer" reply is one WITH an assessment and WITHOUT a
 * prescription: the reasoning is preserved, the decision to decline is explicit.
 *
 * <p>The {@code referral} is Optional because it is transient (never serialized). On LIVE
 * construction the two factories ({@link #assessing}, {@link #prescribing}) require a non-null
 * {@code Referral} and wrap it in {@code Optional.of}; on read-back from persistence the {@link
 * #reconstructed} factory leaves it empty (no synthetic Referral is fabricated).
 */
public record ReferralReply(
    @JsonIgnore Optional<Referral> referral,
    Assessment assessment,
    Optional<Prescription> prescription) {

  public ReferralReply {
    Objects.requireNonNull(assessment, "assessment");
    referral = referral == null ? Optional.empty() : referral;
    prescription = prescription == null ? Optional.empty() : prescription;
  }

  /**
   * A LIVE decline: the specialist assessed but has no prescription to offer. Requires a non-null
   * {@code referral} (the request is present on live construction).
   */
  public static ReferralReply assessing(Referral referral, Assessment assessment) {
    Objects.requireNonNull(referral, "referral");
    return new ReferralReply(Optional.of(referral), assessment, Optional.empty());
  }

  /**
   * A LIVE treat: the specialist assessed AND prescribed. Requires non-null {@code referral} and
   * {@code prescription}.
   */
  public static ReferralReply prescribing(
      Referral referral, Assessment assessment, Prescription prescription) {
    Objects.requireNonNull(referral, "referral");
    Objects.requireNonNull(prescription, "prescription");
    return new ReferralReply(Optional.of(referral), assessment, Optional.of(prescription));
  }

  /**
   * The READ-BACK factory: reconstruct a reply from persisted state. The {@code referral} is left
   * empty (it is transient and not serialized, so no synthetic Referral is fabricated).
   */
  public static ReferralReply reconstructed(
      Assessment assessment, Optional<Prescription> prescription) {
    return new ReferralReply(Optional.empty(), assessment, prescription);
  }

  public boolean hasPrescription() {
    return prescription.isPresent();
  }
}
