package io.nxmatic.rke2lab.doctor.spi;

import io.nxmatic.rke2lab.doctor.records.*;

/**
 * A {@link Clinician} that ADMINISTERS a prescribed treatment — the "hands" tier, distinct from the
 * diagnosing {@link Specialist}. It takes a {@link Prescription} a specialist wrote and applies it
 * to the live system, returning an {@link AdministrationOutcome}. A remediator is a clinician but
 * is NOT a doctor: it does not read observations and does not reason — it executes what a doctor
 * prescribed (clinically a nurse / pharmacist / physiotherapist).
 *
 * <p><b>The doctor stays pure.</b> Diagnosis ({@link Specialist#assess} + {@link
 * Specialist#prescribe}) never touches the live system; ALL live-system risk is confined to this
 * tier. A remediator is therefore an EXPLICITLY invoked actor — it is never called from the
 * diagnostic {@code consult} path. The loop closes between visits on the operator's terms: the
 * doctor PROPOSES (the prescription persists in the record), the operator DISPOSES (authorizes it),
 * and only then is a remediator asked to administer.
 *
 * <p>The {@link Prescription#programRef()} is the routing key: a remediator {@link #administers}
 * exactly the program(s) it is the hands for, mirroring how the Generalist routes a symptom to a
 * specialty. An imperative gesture (e.g. {@link RemediationProgramRef#RESTART_UNIT}) needs an
 * explicit remediator like this; a declarative gesture is administered by the converging engine
 * itself, with no separate executor.
 */
public interface Remediator extends Clinician {

  /** Whether this remediator is the hands for the given program — the prescription routing key. */
  boolean administers(RemediationProgramRef programRef);

  /**
   * Administer the prescribed treatment against the live system and report the outcome. Called only
   * for a {@link Prescription} whose {@link Prescription#programRef()} this remediator {@link
   * #administers}.
   */
  AdministrationOutcome administer(Prescription prescription);
}
