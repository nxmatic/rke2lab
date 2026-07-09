package io.nxmatic.rke2lab.doctor.records;

import java.util.Map;
import java.util.Optional;

/**
 * One captured observation inside a {@code readiness-checkpoint} {@code SeedEnvelope}: the gate's
 * {@code status} ({@code "ok"}, {@code "failed"}, {@code "degraded"}, {@code "deferred-preview"}),
 * a plain {@code summary}, an optional {@link SymptomKind} routing key (present only on a non-ok
 * result — held TYPED, the typing the loose {@code symptom} slug string lacked), and an open {@code
 * details} bag for producer-specific context ({@code source}, {@code probeMode}, …).
 *
 * <p>A nested wire-record (not a top-level coordinate): {@link ReadinessCheckpoint} holds a {@code
 * List<ObservationWire>}. The host builds these from its {@code ObservationView}; OSGi maps them to
 * its own {@code Observation} — no doctor type crosses the seam.
 */
public record ObservationWire(
    String status, String summary, Optional<SymptomKind> symptom, Map<String, Object> details) {

  public ObservationWire {
    symptom = symptom == null ? Optional.empty() : symptom;
    details = details == null ? Map.of() : Map.copyOf(details);
  }
}
