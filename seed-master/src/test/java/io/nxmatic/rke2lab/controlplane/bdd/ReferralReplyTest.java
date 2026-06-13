package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  private final Assessment assessment =
      Assessment.of(
          SchemaRef.of("dbus-tcp/connection-refused/v1"),
          Map.of("unit", "rke2lab-dbus-tcp-system-bus.service"),
          "dbus-TCP endpoint refused the connection");

  private final Prescription prescription =
      Prescription.of(
          RemediationProgramRef.RESTART_UNIT,
          Map.of("unit", "rke2lab-dbus-tcp-system-bus.service"),
          "incus exec master -- systemctl restart rke2lab-dbus-tcp-system-bus.service");

  @Test
  void assessing_reply_has_no_prescription() {
    final ReferralReply reply = ReferralReply.assessing(referral, assessment);

    assertFalse(reply.hasPrescription());

    final Map<String, Object> map = reply.toOutputMap();
    assertTrue(map.containsKey("assessment"));
    assertFalse(map.containsKey("prescription"));
  }

  @Test
  void prescribing_reply_carries_both() {
    final ReferralReply reply = ReferralReply.prescribing(referral, assessment, prescription);

    assertTrue(reply.hasPrescription());

    final Map<String, Object> map = reply.toOutputMap();
    assertTrue(map.containsKey("assessment"));
    assertTrue(map.containsKey("prescription"));

    @SuppressWarnings("unchecked")
    final Map<?, ?> prescriptionMap = (Map<?, ?>) map.get("prescription");
    assertEquals("restart-systemd-unit", prescriptionMap.get("programRef"));
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
