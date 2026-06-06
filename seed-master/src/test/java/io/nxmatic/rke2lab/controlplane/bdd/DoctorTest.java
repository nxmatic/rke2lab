package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.controlplane.config.ConfigLoader;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The doctor: a connection-refused failure routes to the systemd specialist and yields a typed
 * restart-unit prescription; the dossier round-trips through its output-map view unchanged.
 */
class DoctorTest {

  @Test
  void generalist_routes_connection_refused_to_a_restart_unit_prescription() {
    final Generalist generalist = new Generalist(List.of(new DbusTcpSpecialist(config())));
    final Dossier dossier =
        Dossier.failed(Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "test"));

    final RemediationPlan plan = generalist.consult(Symptom.CONNECTION_REFUSED, dossier);

    assertTrue(plan.hasPrescriptions(), "connection-refused should yield a prescription");
    final Prescription prescription = plan.primaryPrescription().orElseThrow();
    assertEquals(RemediationProgramRef.RESTART_UNIT, prescription.programRef());
    assertEquals(DbusTcpSpecialist.ADAPTER_UNIT, prescription.payload().get("unit"));
    assertTrue(
        prescription.humanHint().contains("systemctl restart"),
        "operator-facing hint should be actionable prose");
  }

  @Test
  void dbus_specialist_offers_no_treatment_for_an_unrelated_symptom() {
    final DbusTcpSpecialist specialist = new DbusTcpSpecialist(config());
    final Dossier dossier = Dossier.failed(Symptom.TIMEOUT, "timed out", Map.of());

    assertTrue(
        specialist.diagnose(Symptom.TIMEOUT, dossier).isEmpty(),
        "the dbus specialist only treats connection-refused");
  }

  @Test
  void generalist_with_no_matching_specialist_returns_an_empty_plan() {
    // No specialists registered: timeout routes to NETWORK, but nobody covers it.
    final Generalist generalist = new Generalist(List.of(new DbusTcpSpecialist(config())));
    final Dossier dossier = Dossier.failed(Symptom.TIMEOUT, "timed out", Map.of());

    final RemediationPlan plan = generalist.consult(Symptom.TIMEOUT, dossier);

    assertFalse(plan.hasPrescriptions(), "no covering specialist → empty plan");
    assertEquals(Symptom.TIMEOUT, plan.symptom());
  }

  @Test
  void generalist_recognizes_and_names_cluster_symptoms_without_a_treatment_yet() {
    // Increment D: the cluster symptoms are typed and routed (so they are named in the runbook),
    // but no specialist treats them yet — the doctor returns an empty plan, not a crash.
    final Generalist generalist = new Generalist(List.of(new DbusTcpSpecialist(config())));
    for (Symptom symptom :
        List.of(Symptom.KUBECONFIG_MISSING, Symptom.API_NOT_READY, Symptom.CONTROLLER_NOT_READY)) {
      final Dossier dossier = Dossier.failed(symptom, symptom.id(), Map.of());
      final RemediationPlan plan = generalist.consult(symptom, dossier);
      assertEquals(symptom, plan.symptom(), "the plan names the cluster symptom");
      assertFalse(plan.hasPrescriptions(), "no cluster specialist yet → empty plan for " + symptom);
    }
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

  private static BootstrapConfig config() {
    final Map<String, Map<String, Object>> sections =
        Map.of(
            "incus", Map.of("configDir", "/tmp/rke2lab-bdd-incus"),
            "image", Map.of("sharedFolder", "/tmp/rke2lab-bdd-shared"),
            "worktree", Map.of("dir", "/tmp/rke2lab-bdd-worktree"));
    final Rke2labConfig dto =
        Rke2labConfig.from(ConfigLoader.of(section -> Optional.ofNullable(sections.get(section))));
    return BootstrapConfig.from(dto);
  }
}
