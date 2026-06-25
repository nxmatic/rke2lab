package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.doctor.records.*;
import java.util.List;

/**
 * The doctor's INTERNAL edge: the face the diagnostic model turns toward the rest of our system. A
 * consumer (a pipeline stage, a resource) crosses this port and never touches the hidden actors —
 * the {@code Generalist}, the {@code HealthSystem}, the specialists — so the model's impl evolves
 * freely behind it. Symmetric with manifests-port / netplan-port.
 *
 * <p>The graph behind it is assembled OSGi-side when the {@code HealthSystem} admits a patient, and
 * handed back as this contract; the consumer holds only the interface.
 */
public interface DoctorConsultingService {

  /**
   * The patient consults: route the symptom + the captured {@link Observation} to the relevant
   * specialists and synthesize their replies into a {@link RemediationPlan}.
   */
  RemediationPlan consult(Symptom symptom, Observation observation);

  /**
   * The admitted patient's {@link MedicalRecord}, read through the model's grant-checked access.
   */
  MedicalRecord recordForCurrentPatient();

  /** A one-line cross-patient finding for the symptom, folded across the granted cohort. */
  String cohortFinding(Symptom symptom);

  /**
   * The follow-up coordination at reconstruction: for every resolved expectation on the record,
   * review the problem against the loaded {@link InterventionLedger} and collect the drift letters.
   */
  List<ReferralReply> reviewOpenProblems(MedicalRecord record, InterventionLedger ledger);
}
