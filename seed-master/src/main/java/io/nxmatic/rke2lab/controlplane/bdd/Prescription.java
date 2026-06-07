package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.Map;

/**
 * A corrective action addressed to a remediation program — not shell commands. A specialist writes
 * it; a remediation program fills it. It names a treatment ({@link #programRef}, a typed catalog
 * entry) and gives the indications ({@link #payload}, structured info the program needs), plus a
 * {@link #humanHint} for the operator who _is_ the remediation program today (reads the prose,
 * acts). The dual form is deliberate: prose is rendered into the runbook now; the structured
 * payload keeps the model open for a real executor later, never reducing the action to a string.
 */
public record Prescription(
    RemediationProgramRef programRef, Map<String, Object> payload, String humanHint) {

  public Prescription {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public static Prescription of(
      RemediationProgramRef programRef, Map<String, Object> payload, String humanHint) {
    return new Prescription(programRef, payload, humanHint);
  }

  /** Flat map view; {@code programRef} is the kebab catalog id, never the enum name. */
  public Map<String, Object> toOutputMap() {
    return Map.of("programRef", programRef.id(), "payload", payload, "humanHint", humanHint);
  }
}
