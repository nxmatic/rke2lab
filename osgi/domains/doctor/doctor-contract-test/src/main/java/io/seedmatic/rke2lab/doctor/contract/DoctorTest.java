package io.seedmatic.rke2lab.doctor.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Type-level contracts of the doctor's data model — exhaustive mechanics that underpin the
 * behaviour scenarios in {@code DoctorScenarioTest}: symptom ids parse from their kebab form, and
 * an observation exposes its typed fields (the flat output shape is the codec's concern, proven in
 * {@code ConsultationReportCodecTest}). The dbus-tcp specialist's own contracts moved to {@code
 * DbusTcpSpecialistTest} in doctor-core-test when the specialist became a pure package-private
 * actor.
 */
class DoctorTest {

  @Test
  void cluster_symptoms_parse_from_their_kebab_ids() {
    assertEquals(Optional.of(Symptom.KUBECONFIG_MISSING), Symptom.parse("kubeconfig-missing"));
    assertEquals(Optional.of(Symptom.API_NOT_READY), Symptom.parse("api-not-ready"));
    assertEquals(Optional.of(Symptom.CONTROLLER_NOT_READY), Symptom.parse("controller-not-ready"));
  }

  @Test
  void failed_observation_carries_the_typed_symptom() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "fault-simulation"));

    assertEquals("failed", observation.status());
    assertEquals("dbus refused", observation.summary());
    assertEquals(Optional.of(Symptom.CONNECTION_REFUSED), observation.symptom());
    assertEquals("fault-simulation", observation.details().get("source"));
  }

  @Test
  void ok_observation_carries_no_symptom() {
    final Observation observation = Observation.ok("reachable", Map.of());
    assertTrue(observation.isOk());
    assertTrue(observation.symptom().isEmpty());
  }
}
