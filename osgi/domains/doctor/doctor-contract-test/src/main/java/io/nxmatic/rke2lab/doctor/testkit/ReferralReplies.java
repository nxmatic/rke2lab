package io.nxmatic.rke2lab.doctor.testkit;

import io.nxmatic.rke2lab.doctor.contract.Assessment;
import io.nxmatic.rke2lab.doctor.contract.Prescription;
import io.nxmatic.rke2lab.doctor.contract.ReferralReply;
import io.nxmatic.rke2lab.doctor.contract.SchemaRef;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only factories for {@link ReferralReply} fixtures. A test that builds a {@code
 * RemediationPlan} directly has no live {@code Referral}, so it reconstructs replies (empty
 * referral) carrying a throwaway {@link Assessment} — the reasoning is irrelevant to these tests,
 * but a reply ALWAYS needs one, so the helper supplies it.
 */
public final class ReferralReplies {

  private static final Assessment WHY =
      Assessment.of(SchemaRef.of("test/why/v1"), Map.of(), "test reasoning");

  private ReferralReplies() {}

  /** A treating reply: an assessment plus the given prescription. */
  public static ReferralReply treating(Prescription prescription) {
    return ReferralReply.reconstructed(WHY, Optional.of(prescription));
  }
}
