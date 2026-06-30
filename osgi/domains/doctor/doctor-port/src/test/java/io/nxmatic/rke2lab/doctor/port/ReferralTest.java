package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.nxmatic.rke2lab.doctor.records.*;
import org.junit.jupiter.api.Test;

class ReferralTest {

  @Test
  void holds_the_request_context() {
    final Patient patient = new Patient("organization", "rke2lab", "standalone");
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED,
            "dbus refused",
            java.util.Map.of("source", "endpoint-gate"));
    final MedicalRecord record = new MedicalRecord(patient, java.util.List.of());

    final Referral referral = Referral.of(patient, Symptom.CONNECTION_REFUSED, observation, record);

    assertSame(patient, referral.patient());
    assertEquals(Symptom.CONNECTION_REFUSED, referral.symptom());
    assertSame(observation, referral.observation());
    assertSame(record, referral.record());
  }

  @Test
  void null_patient_is_rejected() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED,
            "dbus refused",
            java.util.Map.of("source", "endpoint-gate"));
    final MedicalRecord record =
        new MedicalRecord(
            new Patient("organization", "rke2lab", "standalone"), java.util.List.of());

    assertThrows(
        NullPointerException.class,
        () -> Referral.of(null, Symptom.CONNECTION_REFUSED, observation, record));
  }

  @Test
  void null_symptom_is_rejected() {
    final Patient patient = new Patient("organization", "rke2lab", "standalone");
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED,
            "dbus refused",
            java.util.Map.of("source", "endpoint-gate"));
    final MedicalRecord record = new MedicalRecord(patient, java.util.List.of());

    assertThrows(NullPointerException.class, () -> Referral.of(patient, null, observation, record));
  }

  @Test
  void null_observation_is_rejected() {
    final Patient patient = new Patient("organization", "rke2lab", "standalone");
    final MedicalRecord record = new MedicalRecord(patient, java.util.List.of());

    assertThrows(
        NullPointerException.class,
        () -> Referral.of(patient, Symptom.CONNECTION_REFUSED, null, record));
  }

  @Test
  void null_record_is_rejected() {
    final Patient patient = new Patient("organization", "rke2lab", "standalone");
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED,
            "dbus refused",
            java.util.Map.of("source", "endpoint-gate"));

    assertThrows(
        NullPointerException.class,
        () -> Referral.of(patient, Symptom.CONNECTION_REFUSED, observation, null));
  }
}
