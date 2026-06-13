package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.Map;
import java.util.Optional;

/**
 * Test-only factories for {@link ReferralReply} fixtures. A test that builds a {@link
 * RemediationPlan} directly has no live {@link Referral}, so it reconstructs replies (empty
 * referral) carrying a throwaway {@link Assessment} — the reasoning is irrelevant to these tests,
 * but a reply ALWAYS needs one, so the helper supplies it.
 */
final class ReferralReplies {

  private static final Assessment WHY =
      Assessment.of(SchemaRef.of("test/why/v1"), Map.of(), "test reasoning");

  private ReferralReplies() {}

  /** A treating reply: an assessment plus the given prescription. */
  static ReferralReply treating(Prescription prescription) {
    return ReferralReply.reconstructed(WHY, Optional.of(prescription));
  }
}
