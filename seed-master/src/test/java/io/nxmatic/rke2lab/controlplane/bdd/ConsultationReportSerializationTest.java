package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the flat, string-keyed serialization of the diagnostic layer (symptom / dossiers / plan) —
 * the form a checkpoint resource registers as an additive Pulumi output. Ids are kebab ({@link
 * Symptom#id()} / {@link RemediationProgramRef#id()}), never enum {@code name()}.
 */
class ConsultationReportSerializationTest {

  @Test
  void report_serializes_to_a_flat_kebab_keyed_map() {
    final Dossier dossier =
        Dossier.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
    final Prescription prescription =
        Prescription.of(
            RemediationProgramRef.RESTART_UNIT, Map.of("unit", "systemd-adapter"), "restart it");
    final RemediationPlan plan =
        new RemediationPlan(
            Symptom.CONNECTION_REFUSED, List.of(prescription), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(dossier), plan);

    final Map<String, Object> map = report.toOutputMap();

    assertEquals("systemd-adapter", map.get("checkpointId"));

    final Map<?, ?> planMap = (Map<?, ?>) map.get("plan");
    assertEquals(Symptom.CONNECTION_REFUSED.id(), planMap.get("symptom"));

    final Map<?, ?> firstPrescription = (Map<?, ?>) ((List<?>) planMap.get("prescriptions")).get(0);
    assertEquals("restart-systemd-unit", firstPrescription.get("programRef"));

    assertInstanceOf(List.class, map.get("dossiers"));
  }
}
