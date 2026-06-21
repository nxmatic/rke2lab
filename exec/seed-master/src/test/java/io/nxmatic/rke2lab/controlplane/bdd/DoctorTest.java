package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.doctor.port.MedicalRecord;
import io.nxmatic.rke2lab.doctor.port.Observation;
import io.nxmatic.rke2lab.doctor.port.Patient;
import io.nxmatic.rke2lab.doctor.port.Referral;
import io.nxmatic.rke2lab.doctor.port.ReferralReply;
import io.nxmatic.rke2lab.doctor.port.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.port.Symptom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Type-level contracts of the doctor's data model — exhaustive mechanics that underpin the
 * behaviour scenarios in {@code DoctorScenarioTest}: a specialist always returns a reply carrying
 * an Assessment (declining a symptom outside its domain still speaks the "why"; treating a symptom
 * in its domain moves the reasoning into the Assessment so the prescription's humanHint is the
 * action only), symptom ids parse from their kebab form, and an observation round-trips through its
 * flat output-map view (the exact keys that flow to Pulumi outputs).
 */
class DoctorTest {

  private static final Patient PATIENT = new Patient("organization", "rke2lab", "dev");

  private static Referral referral(Symptom symptom, Observation observation) {
    return Referral.of(PATIENT, symptom, observation, new MedicalRecord(PATIENT, List.of()));
  }

  @Test
  void declined_symptom_yields_assessment_not_silence() {
    final DbusTcpSpecialist specialist =
        new DbusTcpSpecialist(OperatorConfiguration.mandatory().asBootstrapConfig());
    final Observation observation = Observation.failed(Symptom.TIMEOUT, "timed out", Map.of());

    final ReferralReply reply = specialist.diagnose(referral(Symptom.TIMEOUT, observation));

    assertFalse(reply.hasPrescription(), "the dbus specialist only treats connection-refused");
    assertEquals("dbus-tcp/declined/v1", reply.assessment().schemaRef().id());
    assertTrue(
        reply.assessment().summary().contains("no treatment"),
        () -> "a decline must explain itself: " + reply.assessment().summary());
  }

  @Test
  void connection_refused_yields_assessment_and_prescription() {
    final DbusTcpSpecialist specialist =
        new DbusTcpSpecialist(OperatorConfiguration.mandatory().asBootstrapConfig());
    final Observation observation =
        Observation.failed(Symptom.CONNECTION_REFUSED, "dbus refused", Map.of());

    final ReferralReply reply =
        specialist.diagnose(referral(Symptom.CONNECTION_REFUSED, observation));

    assertTrue(reply.hasPrescription(), "connection-refused is the dbus specialist's treatment");
    assertEquals("dbus-tcp/connection-refused/v1", reply.assessment().schemaRef().id());

    // The prescription's real, host-specific target — the coverage that used to live in the
    // DoctorScenarioTest "targets unit" step before that scenario moved to the generic
    // FakeSpecialist.
    final var prescription = reply.prescription().orElseThrow();
    assertEquals(RemediationProgramRef.RESTART_UNIT, prescription.programRef());
    assertEquals(DbusTcpSpecialist.ADAPTER_UNIT, prescription.payload().get("unit"));

    final String humanHint = prescription.humanHint();
    assertTrue(humanHint.contains("systemctl restart"), () -> humanHint);
    assertFalse(
        humanHint.contains("refused"),
        () ->
            "the reasoning moved into the assessment; humanHint is the action only: " + humanHint);
  }

  @Test
  void cluster_symptoms_parse_from_their_kebab_ids() {
    assertEquals(Optional.of(Symptom.KUBECONFIG_MISSING), Symptom.parse("kubeconfig-missing"));
    assertEquals(Optional.of(Symptom.API_NOT_READY), Symptom.parse("api-not-ready"));
    assertEquals(Optional.of(Symptom.CONTROLLER_NOT_READY), Symptom.parse("controller-not-ready"));
  }

  @Test
  void observation_round_trips_through_its_output_map_view() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "fault-simulation"));

    final Map<String, Object> map = observation.toOutputMap();

    // The flat view carries status + summary + the typed symptom id + details — the exact keys
    // that flow downstream to Pulumi outputs, unchanged by the Map -> Observation retype.
    assertEquals("failed", map.get("status"));
    assertEquals("dbus refused", map.get("summary"));
    assertEquals(Symptom.CONNECTION_REFUSED.id(), map.get(Symptom.ENVELOPE_KEY));
    assertEquals("fault-simulation", map.get("source"));
  }

  @Test
  void ok_observation_carries_no_symptom() {
    final Observation observation = Observation.ok("reachable", Map.of());
    assertTrue(observation.isOk());
    assertTrue(observation.symptom().isEmpty());
    assertFalse(observation.toOutputMap().containsKey(Symptom.ENVELOPE_KEY));
  }
}
