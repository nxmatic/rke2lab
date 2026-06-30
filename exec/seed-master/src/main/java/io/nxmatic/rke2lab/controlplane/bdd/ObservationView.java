package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.world.gateway.port.SymptomKind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The host-flat snapshot a readiness probe produces — the host-side twin of the doctor's {@code
 * io.nxmatic.rke2lab.doctor.records.Observation}, carrying the same {@code status}/{@code
 * summary}/{@code symptom}/{@code details} but typed against the seam's {@link SymptomKind} rather
 * than the bundle-only doctor {@code Symptom}. The host reasons on the typed fields; only {@link
 * #toOutputMap()} renders the flat map, and only at the consult boundary (inside the checkpoint
 * Document payload), where OSGi's {@code Generalist.observationsFrom} reconstructs the doctor
 * {@code Observation} from it. This view never crosses the realm boundary — it is host-internal
 * scaffolding, so it is package-private.
 *
 * <p>{@code status} is the gate's contract value ({@code "ok"}, {@code "failed"}, {@code
 * "degraded"}, {@code "deferred-preview"}); {@code symptom} is present only on a non-ok result and
 * is the typed routing key; {@code details} carries the producer-specific remainder ({@code
 * source}, {@code probeMode}, {@code adapterStatus}, …).
 *
 * <p>Public only so the {@code controlplane.systemd} gate (a separate package) can name it as its
 * probe-contract return type — it never crosses the realm boundary (a {@code controlplane.bdd}
 * type, not a {@code doctor.records} one).
 */
public record ObservationView(
    String status, Optional<SymptomKind> symptom, String summary, Map<String, Object> details) {

  /**
   * The flat-map key under which the symptom slug travels — matches {@code Symptom.ENVELOPE_KEY}.
   */
  static final String SYMPTOM_KEY = "symptom";

  public ObservationView {
    symptom = symptom == null ? Optional.empty() : symptom;
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  /** A reachable/healthy snapshot (no symptom). */
  public static ObservationView ok(String summary, Map<String, Object> details) {
    return new ObservationView("ok", Optional.empty(), summary, details);
  }

  /** A failed snapshot carrying the typed symptom the doctor routes on. */
  public static ObservationView failed(
      SymptomKind symptom, String summary, Map<String, Object> details) {
    return new ObservationView("failed", Optional.of(symptom), summary, details);
  }

  /** A non-ok snapshot of arbitrary status (degraded, deferred-preview, …), optional symptom. */
  public static ObservationView of(
      String status, Optional<SymptomKind> symptom, String summary, Map<String, Object> details) {
    return new ObservationView(status, symptom, summary, details);
  }

  public boolean isOk() {
    return "ok".equalsIgnoreCase(status);
  }

  /**
   * The flat map view: {@code details} plus the canonical {@code status}/{@code summary} keys and,
   * when present, the symptom slug under {@link #SYMPTOM_KEY}. Identical to the doctor {@code
   * Observation.toOutputMap()} shape, so the checkpoint Document the consult carries is unchanged.
   */
  public Map<String, Object> toOutputMap() {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>(details);
    map.put("status", status);
    map.put("summary", summary);
    symptom.ifPresent(s -> map.put(SYMPTOM_KEY, s.slug()));
    return Map.copyOf(map);
  }
}
