package io.nxmatic.rke2lab.controlplane.bdd;

/**
 * The live doctor's standing access to patient records. Unlike a one-shot reconstruction, the
 * registry is held by the {@link Generalist} for its lifetime, so {@link #recordFor} must never
 * throw: a reconstruction failure is the implementation's concern and degrades to an empty {@code
 * MedicalRecord(patient, List.of())}, never an exception the diagnosis path has to defend against.
 * A pure in-memory implementation returning synthetic records is the test seam.
 */
public interface MedicalRecordRegistry {

  MedicalRecord recordFor(Patient patient);
}
