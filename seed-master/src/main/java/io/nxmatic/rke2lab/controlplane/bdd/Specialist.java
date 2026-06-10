package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.Optional;

/**
 * A domain expert the Generalist routes to. Given a symptom and the captured {@link Observation},
 * it reads the observation first (the snapshot _is_ the Status/Conditions — the cert-manager way),
 * probes further only if needed, and writes a {@link Prescription} — or none if it has no treatment
 * for what it sees.
 *
 * <p>This interface is the AI-ready seam: {@code diagnose(Symptom, Observation)} makes no
 * assumption about implementation. Phase 1 is a Java class reading the objects in-process; a future
 * Phase 2 could back the same interface with an out-of-process tool (serialize the observation,
 * call the tool, deserialize the prescription). The Generalist does not know or care which.
 */
public interface Specialist {

  /** Domain this specialist covers — the Generalist's routing key. */
  Specialty domain();

  /**
   * Diagnose the symptom against the observation. Returns a prescription, or empty when this
   * specialist has nothing to offer for what the observation shows (the Generalist then relies on
   * the others).
   */
  Optional<Prescription> diagnose(Symptom symptom, Observation observation);
}
