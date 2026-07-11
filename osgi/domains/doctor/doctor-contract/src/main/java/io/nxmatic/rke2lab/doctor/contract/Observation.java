package io.nxmatic.rke2lab.doctor.contract;

import java.util.Map;
import java.util.Optional;

/**
 * The captured snapshot a readiness probe produces — the typed successor of the probe's former
 * {@code Map<String,Object>} envelope. The doctor reads it as typed fields (the cert-manager "the
 * snapshot _is_ the Status/Conditions" idea — the specialist reads the observation first); its flat
 * output shape, when one is filed, is produced by {@code SeedCodec.toMap} through the graft path,
 * not by a hand-rolled projector.
 *
 * <p>{@code status} is the gate's contract value ({@code "ok"}, {@code "failed"}, {@code
 * "degraded"}, {@code "deferred-preview"}); {@code symptom} is present only on a non-ok result and
 * is the typed routing key the Generalist uses (never parsed from prose); {@code details} carries
 * the producer-specific remainder ({@code source}, {@code probeMode}, {@code adapterStatus}, …).
 */
public record Observation(
    String status, Optional<Symptom> symptom, String summary, Map<String, Object> details) {

  public Observation {
    symptom = symptom == null ? Optional.empty() : symptom;
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  /** A reachable/healthy snapshot (no symptom). */
  public static Observation ok(String summary, Map<String, Object> details) {
    return new Observation("ok", Optional.empty(), summary, details);
  }

  /** A failed snapshot carrying the typed symptom the doctor routes on. */
  public static Observation failed(Symptom symptom, String summary, Map<String, Object> details) {
    return new Observation("failed", Optional.of(symptom), summary, details);
  }

  /** A non-ok snapshot of arbitrary status (degraded, deferred-preview, …), optional symptom. */
  public static Observation of(
      String status, Optional<Symptom> symptom, String summary, Map<String, Object> details) {
    return new Observation(status, symptom, summary, details);
  }

  public boolean isOk() {
    return "ok".equalsIgnoreCase(status);
  }
}
