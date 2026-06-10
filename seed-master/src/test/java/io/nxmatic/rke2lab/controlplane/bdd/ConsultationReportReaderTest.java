package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The inverse contract of {@link ConsultationReport#toOutputMap()} and its nested {@code
 * toOutputMap}s: feed a real round-tripped output map back into {@link ConsultationReportReader},
 * assert the report rebuilds identically. Also pins the tolerance/additivity guarantee (unknown
 * keys survive into {@code details}, absent optional keys degrade to empty) and the three hard
 * requirements that make reconstruction impossible (no map / no checkpointId / no parseable
 * diagnosis).
 */
class ConsultationReportReaderTest {

  private static ConsultationReport sampleReport() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
    final Prescription prescription =
        Prescription.of(
            RemediationProgramRef.RESTART_UNIT, Map.of("unit", "systemd-adapter"), "restart it");
    final RemediationPlan plan =
        new RemediationPlan(
            Symptom.CONNECTION_REFUSED, List.of(prescription), "adapter unreachable");
    return new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(observation), plan);
  }

  @Test
  void round_trips_a_full_report() {
    final ConsultationReport rebuilt =
        ConsultationReportReader.fromOutputMap(sampleReport().toOutputMap()).orElseThrow();

    assertEquals("systemd-adapter", rebuilt.checkpointId());
    assertEquals(Symptom.CONNECTION_REFUSED, rebuilt.symptom());
    assertEquals("adapter unreachable", rebuilt.plan().generalistSummary());

    final Prescription rebuiltPrescription = rebuilt.plan().primaryPrescription().orElseThrow();
    assertEquals(RemediationProgramRef.RESTART_UNIT, rebuiltPrescription.programRef());
    assertEquals("systemd-adapter", rebuiltPrescription.payload().get("unit"));
    assertEquals("restart it", rebuiltPrescription.humanHint());

    final Observation rebuiltObservation = rebuilt.observations().get(0);
    assertEquals("failed", rebuiltObservation.status());
    assertEquals("dbus refused", rebuiltObservation.summary());
    assertEquals(Optional.of(Symptom.CONNECTION_REFUSED), rebuiltObservation.symptom());
    assertEquals("endpoint-gate", rebuiltObservation.details().get("source"));
  }

  @Test
  void null_returns_empty() {
    assertTrue(ConsultationReportReader.fromOutputMap(null).isEmpty());
  }

  @Test
  void non_map_returns_empty() {
    assertTrue(ConsultationReportReader.fromOutputMap("not a map").isEmpty());
  }

  @Test
  void missing_checkpointId_returns_empty() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    raw.remove("checkpointId");
    assertTrue(ConsultationReportReader.fromOutputMap(raw).isEmpty());
  }

  @Test
  void missing_plan_returns_empty() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    raw.remove("plan");
    assertTrue(ConsultationReportReader.fromOutputMap(raw).isEmpty());
  }

  @Test
  void unparseable_plan_symptom_returns_empty() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    final Map<String, Object> plan = new LinkedHashMap<>((Map<String, Object>) raw.get("plan"));
    plan.put("symptom", "not-a-real-symptom");
    raw.put("plan", plan);
    assertTrue(ConsultationReportReader.fromOutputMap(raw).isEmpty());
  }

  @Test
  void unknown_observation_key_survives_into_details() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED,
            "dbus refused",
            Map.of("source", "endpoint-gate", "futureField", "tomorrow"));
    final RemediationPlan plan =
        new RemediationPlan(Symptom.CONNECTION_REFUSED, List.of(), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(observation), plan);

    final Observation rebuilt =
        ConsultationReportReader.fromOutputMap(report.toOutputMap())
            .orElseThrow()
            .observations()
            .get(0);

    assertEquals("tomorrow", rebuilt.details().get("futureField"));
    assertEquals("endpoint-gate", rebuilt.details().get("source"));
    // canonical keys must NOT leak into details
    assertFalse(rebuilt.details().containsKey("status"));
    assertFalse(rebuilt.details().containsKey("summary"));
    assertFalse(rebuilt.details().containsKey(Symptom.ENVELOPE_KEY));
  }

  @Test
  void extra_top_level_key_does_not_break_reconstruction() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    raw.put("correspondence", Map.of("seenBy", "future-doctor"));
    assertTrue(ConsultationReportReader.fromOutputMap(raw).isPresent());
  }

  @Test
  void missing_observations_yields_empty_list() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    raw.remove("observations");
    final ConsultationReport rebuilt = ConsultationReportReader.fromOutputMap(raw).orElseThrow();
    assertTrue(rebuilt.observations().isEmpty());
  }

  @Test
  void missing_prescriptions_yields_empty_list() {
    final RemediationPlan plan =
        new RemediationPlan(Symptom.CONNECTION_REFUSED, List.of(), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(), plan);
    final Map<String, Object> raw = new LinkedHashMap<>(report.toOutputMap());
    final Map<String, Object> planMap = new LinkedHashMap<>((Map<String, Object>) raw.get("plan"));
    planMap.remove("prescriptions");
    raw.put("plan", planMap);

    final ConsultationReport rebuilt = ConsultationReportReader.fromOutputMap(raw).orElseThrow();
    assertTrue(rebuilt.plan().prescriptions().isEmpty());
  }

  @Test
  void prescription_without_payload_yields_empty_map() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    final Map<String, Object> planMap = new LinkedHashMap<>((Map<String, Object>) raw.get("plan"));
    final List<?> prescriptions = (List<?>) planMap.get("prescriptions");
    final Map<String, Object> presc =
        new LinkedHashMap<>((Map<String, Object>) prescriptions.get(0));
    presc.remove("payload");
    planMap.put("prescriptions", List.of(presc));
    raw.put("plan", planMap);

    final Prescription rebuilt =
        ConsultationReportReader.fromOutputMap(raw)
            .orElseThrow()
            .plan()
            .primaryPrescription()
            .orElseThrow();
    assertTrue(rebuilt.payload().isEmpty());
  }

  @Test
  void unparseable_prescription_programRef_is_skipped() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    final Map<String, Object> planMap = new LinkedHashMap<>((Map<String, Object>) raw.get("plan"));
    final Map<String, Object> garbage =
        Map.of("programRef", "no-such-program", "payload", Map.of(), "humanHint", "ignored");
    planMap.put("prescriptions", List.of(garbage));
    raw.put("plan", planMap);

    final ConsultationReport rebuilt = ConsultationReportReader.fromOutputMap(raw).orElseThrow();
    assertTrue(rebuilt.plan().prescriptions().isEmpty());
  }
}
