package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.contract.MedicalRecord;
import io.nxmatic.rke2lab.doctor.contract.Patient;
import java.util.List;

/**
 * The live doctor's standing access to patient records. Unlike a one-shot reconstruction, the
 * registry is held by the {@link Generalist} for its lifetime, so {@link #recordFor} must never
 * throw: a reconstruction failure is the implementation's concern and degrades to an empty {@code
 * MedicalRecord(patient, List.of())}, never an exception the diagnosis path has to defend against.
 * A pure in-memory implementation returning synthetic records is the test seam.
 */
public interface MedicalRecordRegistry {

  MedicalRecord recordFor(Patient patient);

  /**
   * The records of the current patient's cohort — the sibling patients sharing the backend. The
   * {@link ClinicalAccess} applies the {@code (ClinicianId, Patient)} grant filter over this list;
   * an implementation with no notion of siblings returns just the current patient's record.
   */
  default List<MedicalRecord> cohortFor(Patient current) {
    return List.of(recordFor(current));
  }
}
