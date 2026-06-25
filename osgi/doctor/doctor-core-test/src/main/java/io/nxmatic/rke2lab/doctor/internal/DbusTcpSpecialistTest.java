package io.nxmatic.rke2lab.doctor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The dbus-tcp specialist, white-box in-container: it is package-private in {@code doctor-core}, so
 * this fragment reaches it through the host loader. Proves the actor that used to live config-bound
 * on the host now reads its endpoint + node off the OBSERVATION the producer stamped — a decline
 * still speaks the "why", a connection-refused prescribes the unit restart with the host/node taken
 * from the snapshot, not from {@code BootstrapConfig}.
 */
class DbusTcpSpecialistTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  private static Referral referral(Symptom symptom, Observation observation) {
    return Referral.of(PATIENT, symptom, observation, new MedicalRecord(PATIENT, List.of()));
  }

  @Test
  void declined_symptom_yields_assessment_not_silence() {
    final DbusTcpSpecialist specialist = new DbusTcpSpecialist();
    final Observation observation = Observation.failed(Symptom.TIMEOUT, "timed out", Map.of());

    final ReferralReply reply = specialist.diagnose(referral(Symptom.TIMEOUT, observation));

    assertFalse(reply.hasPrescription(), "the dbus specialist only treats connection-refused");
    assertEquals("dbus-tcp/declined/v1", reply.assessment().schemaRef().id());
    assertTrue(
        reply.assessment().summary().contains("no treatment"),
        () -> "a decline must explain itself: " + reply.assessment().summary());
  }

  @Test
  void connection_refused_yields_assessment_and_prescription_from_the_observation() {
    final DbusTcpSpecialist specialist = new DbusTcpSpecialist();
    // The producer (the endpoint gate) stamps the endpoint + node as flat details keys; the
    // specialist reads them off the observation rather than reaching back to host config.
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED,
            "dbus refused",
            Map.of("adapterHost", "10.0.0.7", "adapterPort", "55555", "nodeName", "seed-master"));

    final ReferralReply reply =
        specialist.diagnose(referral(Symptom.CONNECTION_REFUSED, observation));

    assertTrue(reply.hasPrescription(), "connection-refused is the dbus specialist's treatment");
    assertEquals("dbus-tcp/connection-refused/v1", reply.assessment().schemaRef().id());
    assertTrue(
        reply.assessment().summary().contains("10.0.0.7:55555"),
        () -> "the endpoint read off the observation must appear: " + reply.assessment().summary());

    final var prescription = reply.prescription().orElseThrow();
    assertEquals(RemediationProgramRef.RESTART_UNIT, prescription.programRef());
    assertEquals(DbusTcpSpecialist.ADAPTER_UNIT, prescription.payload().get("unit"));

    // The humanHint is the incus egress command — built from the specialist's single format helper,
    // with the node read off the observation. Asserting via the helper pins the contract without
    // re-spelling the format here.
    final String humanHint = prescription.humanHint();
    assertEquals(
        DbusTcpSpecialist.restartUnitCommand("seed-master", DbusTcpSpecialist.ADAPTER_UNIT),
        humanHint,
        "the hint is the helper's format with the node taken from the observation");
    assertFalse(
        humanHint.contains("refused"),
        () ->
            "the reasoning moved into the assessment; humanHint is the action only: " + humanHint);
  }
}
