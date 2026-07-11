package io.nxmatic.rke2lab.doctor.spi;

import io.nxmatic.rke2lab.doctor.contract.Assessment;
import io.nxmatic.rke2lab.doctor.contract.ClinicianId;
import io.nxmatic.rke2lab.doctor.contract.Observation;
import io.nxmatic.rke2lab.doctor.contract.Prescription;
import io.nxmatic.rke2lab.doctor.contract.Referral;
import io.nxmatic.rke2lab.doctor.contract.ReferralReply;
import io.nxmatic.rke2lab.doctor.contract.Specialty;
import java.util.Optional;

/**
 * A domain expert the Generalist routes to. Given a {@link Referral} (the typed request carrying
 * the symptom, the captured {@link Observation}, and the patient's record), it reads the
 * observation first (the snapshot _is_ the Status/Conditions — the cert-manager way) and answers in
 * two acts: it ALWAYS {@link #assess assesses} (the "why"), and it {@link #prescribe prescribes}
 * only when it has a treatment for what the observation shows.
 *
 * <p>Separating the two acts is what lets the Generalist interpose between them — it is where the
 * efficacy-first gate will sit (consult the history before authorizing a treatment). The Generalist
 * assembles the two pieces into a {@link ReferralReply}; the specialist never names the reply type.
 *
 * <p>This interface is the AI-ready seam: neither verb assumes an implementation. Phase 1 is a Java
 * class reading the objects in-process; a future Phase 2 could back the same interface with an
 * out-of-process tool. The Generalist does not know or care which.
 */
public interface Specialist extends Clinician {

  /** Domain this specialist covers — the Generalist's routing key. */
  Specialty domain();

  /**
   * Assess the referral — ALWAYS the first act, ALWAYS present. The {@link Assessment} is the
   * "why": what the observation shows for this domain, present even when no treatment follows. It
   * is the single source of the facts the specialist derived; {@link #prescribe} reads them back
   * rather than re-deriving from the observation.
   */
  Assessment assess(Referral referral);

  /**
   * Prescribe a treatment for this assessed referral, or decline. Returns a {@link Prescription}
   * only when this specialist has a treatment for what the {@code assessment} shows; an empty
   * result is an explicit, reasoned decline (the assessment still carries the "why") — never
   * silence.
   */
  Optional<Prescription> prescribe(Referral referral, Assessment assessment);

  /**
   * A specialist's identity defaults to its specialty kebab-cased, e.g. CLUSTER_API →
   * "cluster-api".
   */
  @Override
  default ClinicianId clinicianId() {
    return new ClinicianId(domain().name().toLowerCase().replace('_', '-'));
  }
}
