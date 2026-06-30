package io.nxmatic.rke2lab.doctor.spi;

import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.Symptom;

/**
 * The doctor's INTERNAL reasoning surface — the record-typed routing/narration the {@code
 * Generalist} performs, kept DELIBERATELY off the {@code ConsultingService} seam so the host can
 * never call it (the host crosses only {@code consult(Document)}; it holds no doctor record type).
 * This is a bundle-side contract (doctor-spi, {@code type=model}), reached by the doctor's own
 * in-container actor tests via {@code consultingService.adapt(ClinicalReasoning.class)} — the same
 * face-by-capability idiom OSGi's {@code adapt} uses — so the reasoning verbs are explicitly
 * identified and exercised without widening the seam.
 */
public interface ClinicalReasoning {

  /**
   * The patient consults: route the symptom + the captured {@link Observation} to the relevant
   * specialists and synthesize their replies into a {@link RemediationPlan}. The routing core that
   * {@code ConsultingService.consult(Document)} drives internally.
   */
  RemediationPlan consult(Symptom symptom, Observation observation);

  /**
   * The one-line consultation narration for the symptom — "consulted with N prior visit(s); SYMPTOM
   * seen K× before" — folded over the admitted patient's own record. Twin of {@link
   * #cohortFinding(Symptom)}.
   */
  String consultedLine(Symptom symptom);

  /** A one-line cross-patient finding for the symptom, folded across the granted cohort. */
  String cohortFinding(Symptom symptom);
}
