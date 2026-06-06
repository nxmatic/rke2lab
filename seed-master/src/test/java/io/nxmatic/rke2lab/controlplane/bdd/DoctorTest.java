package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Type-level contracts of the doctor's data model — exhaustive mechanics that underpin the
 * behaviour scenarios in {@code DoctorScenarioTest}: a specialist declines a symptom outside its
 * domain, symptom ids parse from their kebab form, and a dossier round-trips through its flat
 * output-map view (the exact keys that flow to Pulumi outputs).
 */
class DoctorTest {

  @Test
  void dbus_specialist_offers_no_treatment_for_an_unrelated_symptom() {
    final DbusTcpSpecialist specialist =
        new DbusTcpSpecialist(OperatorConfiguration.mandatory().asBootstrapConfig());
    final Dossier dossier = Dossier.failed(Symptom.TIMEOUT, "timed out", Map.of());

    assertTrue(
        specialist.diagnose(Symptom.TIMEOUT, dossier).isEmpty(),
        "the dbus specialist only treats connection-refused");
  }

  @Test
  void cluster_symptoms_parse_from_their_kebab_ids() {
    assertEquals(Optional.of(Symptom.KUBECONFIG_MISSING), Symptom.parse("kubeconfig-missing"));
    assertEquals(Optional.of(Symptom.API_NOT_READY), Symptom.parse("api-not-ready"));
    assertEquals(Optional.of(Symptom.CONTROLLER_NOT_READY), Symptom.parse("controller-not-ready"));
  }

  @Test
  void dossier_round_trips_through_its_output_map_view() {
    final Dossier dossier =
        Dossier.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "fault-simulation"));

    final Map<String, Object> map = dossier.toOutputMap();

    // The flat view carries status + summary + the typed symptom id + details — the exact keys
    // that flow downstream to Pulumi outputs, unchanged by the Map -> Dossier retype.
    assertEquals("failed", map.get("status"));
    assertEquals("dbus refused", map.get("summary"));
    assertEquals(Symptom.CONNECTION_REFUSED.id(), map.get(Symptom.ENVELOPE_KEY));
    assertEquals("fault-simulation", map.get("source"));
  }

  @Test
  void ok_dossier_carries_no_symptom() {
    final Dossier dossier = Dossier.ok("reachable", Map.of());
    assertTrue(dossier.isOk());
    assertTrue(dossier.symptom().isEmpty());
    assertFalse(dossier.toOutputMap().containsKey(Symptom.ENVELOPE_KEY));
  }
}
