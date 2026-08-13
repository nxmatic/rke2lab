package io.seedmatic.rke2lab.doctor.contract;

import java.util.List;
import java.util.Optional;

/**
 * The Generalist's synthesis: the symptom that was diagnosed, the {@link ReferralReply}s the
 * consulted specialists returned (each carrying an {@link Assessment} — the "why" — and optionally
 * a {@link Prescription} — the action), and a one-line generalist summary (its global view). Flows
 * into the runbook node's Mitigation section and the inline log. An empty replies list is a valid
 * plan — the generalist saw the symptom but no specialist replied. The {@link #prescriptions()}
 * view is derived from the replies.
 */
public record RemediationPlan(
    Symptom symptom, List<ReferralReply> replies, String generalistSummary) {

  public RemediationPlan {
    if (symptom == null) {
      throw new IllegalArgumentException("symptom cannot be null");
    }
    replies = replies == null ? List.of() : List.copyOf(replies);
  }

  /** The prescriptions carried by the replies (replies without a prescription contribute none). */
  public List<Prescription> prescriptions() {
    return replies.stream().flatMap(reply -> reply.prescription().stream()).toList();
  }

  public boolean hasPrescriptions() {
    return !prescriptions().isEmpty();
  }

  /** The first prescription, if any — convenience for single-treatment cases. */
  public Optional<Prescription> primaryPrescription() {
    final List<Prescription> prescriptions = prescriptions();
    return prescriptions.isEmpty() ? Optional.empty() : Optional.of(prescriptions.get(0));
  }
}
