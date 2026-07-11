package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.seed.broker.port.ObservationWire;
import io.nxmatic.rke2lab.seed.broker.port.SymptomKind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The host-flat snapshot a readiness probe produces — the host-side twin of the doctor's {@code
 * io.nxmatic.rke2lab.doctor.contract.Observation}, carrying the same {@code status}/{@code
 * summary}/{@code symptom}/{@code details} but typed against the seam's {@link SymptomKind} rather
 * than the bundle-only doctor {@code Symptom}. The host reasons on the typed fields; it renders in
 * two distinct places, at two distinct boundaries:
 *
 * <ul>
 *   <li>{@link #toWire()} — the seam boundary: the observation as an {@link ObservationWire} nested
 *       in the {@code readiness-checkpoint} Document the consult carries, where OSGi's {@code
 *       Generalist} reconstructs the doctor {@code Observation}.
 *   <li>{@link #toOutputMap()} — the Pulumi output boundary: the flat map the launch summary fans
 *       into the resource's outputs (host-internal; never crosses the realm boundary).
 * </ul>
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

  /** Output-map key under which a non-ok snapshot records its symptom slug. */
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
   * The seam wire view: this host snapshot as an {@link ObservationWire} nested in a {@code
   * readiness-checkpoint}. The typed {@link SymptomKind} carries straight through (the codec
   * renders it as its slug); {@code details} is carried as-is. OSGi's {@code
   * Generalist.observationsFrom} maps the wire to the doctor {@code Observation} — no doctor type
   * crosses the seam.
   */
  public ObservationWire toWire() {
    return new ObservationWire(status, summary, symptom, details);
  }

  /**
   * The Pulumi output view: {@code details} plus the canonical {@code status}/{@code summary} keys
   * and, when present, the symptom slug under {@link #SYMPTOM_KEY}. This is the host-internal flat
   * map the launch summary fans into the resource's Pulumi outputs — a distinct concern from {@link
   * #toWire()} (the seam), and it never crosses the realm boundary.
   */
  public Map<String, Object> toOutputMap() {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>(details);
    map.put("status", status);
    map.put("summary", summary);
    symptom.ifPresent(s -> map.put(SYMPTOM_KEY, s.slug()));
    return Map.copyOf(map);
  }
}
