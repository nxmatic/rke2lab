package io.nxmatic.rke2lab.doctor.contract;

import java.util.Objects;

/**
 * A typed REQUEST a generalist sends to a specialist in the referral round-trip. It carries the
 * patient identity, the symptom (the "why" behind the referral), the observation the specialist
 * reads first, and a transient, read-only reference to the patient's full medical record (so a
 * future history-aware specialist MAY consult history during {@code diagnose}; the current driver
 * specialist does not yet — this is a documented seam). A referral is a transient request and is
 * NEVER serialized.
 */
public record Referral(
    Patient patient, Symptom symptom, Observation observation, MedicalRecord record) {

  public Referral {
    Objects.requireNonNull(patient, "patient");
    Objects.requireNonNull(symptom, "symptom");
    Objects.requireNonNull(observation, "observation");
    Objects.requireNonNull(record, "record");
  }

  public static Referral of(
      Patient patient, Symptom symptom, Observation observation, MedicalRecord record) {
    return new Referral(patient, symptom, observation, record);
  }
}
