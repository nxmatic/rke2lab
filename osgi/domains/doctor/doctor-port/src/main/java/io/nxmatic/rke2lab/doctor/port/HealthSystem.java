package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.doctor.records.Patient;

/**
 * The institution — the seam the host crosses to obtain a doctor. The health system already HOLDS
 * its infrastructure (the EHR, the intervention ledger) and employs its clinicians; the host does
 * not hand those over at the bedside. It presents only a patient's identity, and the institution
 * admits them and assigns the attending doctor.
 *
 * <p>Resolved from the embedded framework ({@code awaitService(HealthSystem.class)}): the doctor is
 * built OSGi-side, where its specialists arrive by Declarative Services, so the diagnostic model
 * and its specialists never cross to the host — only this seam and the returned {@link
 * ConsultingService} (both port types) do.
 */
public interface HealthSystem {

  /**
   * Admit the patient for a run and return the attending doctor's consulting contract. The run's
   * grants are minted here (self + cohort), and the employed clinicians are wired to the admitted
   * patient.
   */
  ConsultingService admit(Patient patient);
}
