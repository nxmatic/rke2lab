package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.world.gateway.port.Document;

/**
 * The doctor's INTERNAL edge: the face the diagnostic model turns toward the rest of our system. A
 * consumer (a pipeline stage, a resource) crosses this port and never touches the hidden actors —
 * the {@code Generalist}, the {@code HealthSystem}, the specialists — so the model's impl evolves
 * freely behind it. Symmetric with manifests-port / netplan-port.
 *
 * <p>The graph behind it is assembled OSGi-side when the {@code HealthSystem} admits a patient, and
 * handed back as this contract; the consumer holds only the interface. No {@code doctor.records}
 * type crosses this port: the medical record and intervention ledger are rebuilt OSGi-side from the
 * host journals, never returned to the host.
 */
public interface ConsultingService {

  /**
   * Consult on a checkpoint: route its symptom + observation to the specialists and synthesize the
   * narration and the rendered AsciiDoc diagnosis, returned as a {@code consultation} Document. The
   * twin of {@link io.nxmatic.rke2lab.world.gateway.port.ReadinessAuthority#assess} — same
   * checkpoint, the consulting concern rather than the provisioning verdict. The ONLY consult verb
   * that crosses the seam; the record-typed routing/narration is the bundle-side {@link
   * io.nxmatic.rke2lab.doctor.spi.ClinicalReasoning}, reached via {@link #adapt}.
   */
  Document consult(Document checkpoint);

  /**
   * The follow-up coordination at reconstruction: rebuild the admitted patient's record (through
   * the model's grant-checked access) and the intervention ledger (from the host intervention
   * journal), then for every resolved expectation review the problem against the ledger and persist
   * any inferred drift. No-arg: the trace takes the patient from the held access and the ledger
   * from the journal — nothing is read from the caller. The record and ledger never cross back to
   * the host.
   */
  void reviewDrift();

  /**
   * The face of this service that implements {@code type}, or {@code null} if it does not — the
   * face-by-capability idiom (cf. OSGi's {@code adapt}). The host uses only the seam verbs above;
   * the doctor's own in-container tests reach the bundle-side {@link
   * io.nxmatic.rke2lab.doctor.spi.ClinicalReasoning} through this, without widening the seam.
   */
  default <T> T adapt(Class<T> type) {
    return type.isInstance(this) ? type.cast(this) : null;
  }
}
