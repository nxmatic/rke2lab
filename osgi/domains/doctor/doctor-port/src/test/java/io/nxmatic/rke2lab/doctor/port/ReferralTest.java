package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.seed.broker.port.Patient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReferralTest {

  @Test
  void holds_the_request_context() {
    final Patient patient = new Patient("organization", "rke2lab", "standalone");
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
    final MedicalRecord record = new MedicalRecord(patient, List.of());

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
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
    final MedicalRecord record =
        new MedicalRecord(new Patient("organization", "rke2lab", "standalone"), List.of());

    assertThrows(
        NullPointerException.class,
        () -> Referral.of(null, Symptom.CONNECTION_REFUSED, observation, record));
  }

  @Test
  void null_symptom_is_rejected() {
    final Patient patient = new Patient("organization", "rke2lab", "standalone");
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
    final MedicalRecord record = new MedicalRecord(patient, List.of());

    assertThrows(NullPointerException.class, () -> Referral.of(patient, null, observation, record));
  }

  @Test
  void null_observation_is_rejected() {
    final Patient patient = new Patient("organization", "rke2lab", "standalone");
    final MedicalRecord record = new MedicalRecord(patient, List.of());

    assertThrows(
        NullPointerException.class,
        () -> Referral.of(patient, Symptom.CONNECTION_REFUSED, null, record));
  }

  @Test
  void null_record_is_rejected() {
    final Patient patient = new Patient("organization", "rke2lab", "standalone");
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));

    assertThrows(
        NullPointerException.class,
        () -> Referral.of(patient, Symptom.CONNECTION_REFUSED, observation, null));
  }
}
