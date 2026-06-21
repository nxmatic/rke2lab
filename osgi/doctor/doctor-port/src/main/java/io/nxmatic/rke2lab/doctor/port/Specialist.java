package io.nxmatic.rke2lab.doctor.port;

/**
 * A domain expert the Generalist routes to. Given a {@link Referral} (the typed request carrying
 * the symptom, the captured {@link Observation}, and the patient's record), it reads the
 * observation first (the snapshot _is_ the Status/Conditions — the cert-manager way), probes
 * further only if needed, and returns a {@link ReferralReply}.
 *
 * <p>This interface is the AI-ready seam: {@code diagnose(Referral)} makes no assumption about
 * implementation. Phase 1 is a Java class reading the objects in-process; a future Phase 2 could
 * back the same interface with an out-of-process tool (serialize the referral, call the tool,
 * deserialize the reply). The Generalist does not know or care which.
 */
public interface Specialist extends Clinician {

  /** Domain this specialist covers — the Generalist's routing key. */
  Specialty domain();

  /**
   * Diagnose the referral. ALWAYS returns a reply carrying an {@link Assessment} (the "why",
   * present even when the specialist declines), and a {@link Prescription} only when this
   * specialist has a treatment for what the observation shows. A reply without a prescription is an
   * explicit, reasoned decline — never silence.
   */
  ReferralReply diagnose(Referral referral);

  /**
   * A specialist's identity defaults to its specialty kebab-cased, e.g. CLUSTER_API →
   * "cluster-api".
   */
  @Override
  default ClinicianId clinicianId() {
    return new ClinicianId(domain().name().toLowerCase().replace('_', '-'));
  }
}
