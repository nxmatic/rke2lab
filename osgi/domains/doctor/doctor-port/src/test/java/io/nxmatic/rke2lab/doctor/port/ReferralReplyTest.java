package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.systemd.port.SystemdUnitId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReferralReplyTest {

  private final Patient patient = new Patient("organization", "rke2lab", "standalone");

  private final Observation observation =
      Observation.failed(
          Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));

  private final MedicalRecord record = new MedicalRecord(patient, List.of());

  private final Referral referral =
      Referral.of(patient, Symptom.CONNECTION_REFUSED, observation, record);

  private static final String DBUS_UNIT = SystemdUnitId.DBUS_TCP_SYSTEM_BUS.serviceUnitName();

  private final Assessment assessment =
      Assessment.of(
          SchemaRef.of("dbus-tcp/connection-refused/v1"),
          Map.of("unit", DBUS_UNIT),
          "dbus-TCP endpoint refused the connection");

  private final Prescription prescription =
      Prescription.of(
          RemediationProgramRef.RESTART_UNIT,
          Map.of("unit", DBUS_UNIT),
          "incus exec master -- systemctl restart " + DBUS_UNIT);

  @Test
  void assessing_reply_has_no_prescription() {
    final ReferralReply reply = ReferralReply.assessing(referral, assessment);

    assertFalse(reply.hasPrescription());
    assertTrue(reply.referral().isPresent());
  }

  @Test
  void prescribing_reply_carries_both() {
    final ReferralReply reply = ReferralReply.prescribing(referral, assessment, prescription);

    assertTrue(reply.hasPrescription());
    assertEquals(
        RemediationProgramRef.RESTART_UNIT, reply.prescription().orElseThrow().programRef());
  }

  @Test
  void reconstructed_reply_has_empty_referral() {
    final ReferralReply reply = ReferralReply.reconstructed(assessment, Optional.empty());

    assertTrue(reply.referral().isEmpty());
    assertFalse(reply.hasPrescription());
  }

  @Test
  void null_assessment_is_rejected() {
    assertThrows(
        NullPointerException.class, () -> ReferralReply.prescribing(referral, null, prescription));
  }
}
