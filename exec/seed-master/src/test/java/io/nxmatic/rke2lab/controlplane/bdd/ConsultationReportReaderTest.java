package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.port.Assessment;
import io.nxmatic.rke2lab.doctor.port.Checkpoint;
import io.nxmatic.rke2lab.doctor.port.ConsultationReport;
import io.nxmatic.rke2lab.doctor.port.ConsultationReportReader;
import io.nxmatic.rke2lab.doctor.port.Observation;
import io.nxmatic.rke2lab.doctor.port.Prescription;
import io.nxmatic.rke2lab.doctor.port.ReferralReply;
import io.nxmatic.rke2lab.doctor.port.RemediationPlan;
import io.nxmatic.rke2lab.doctor.port.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.port.SchemaRef;
import io.nxmatic.rke2lab.doctor.port.Symptom;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import io.nxmatic.rke2lab.systemd.port.SystemdUnitId;
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
            Symptom.CONNECTION_REFUSED,
            List.of(ReferralReplies.treating(prescription)),
            "adapter unreachable");
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
    final Map<String, Object> plan = new LinkedHashMap<>(asMap(raw.get("plan")));
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
  void missing_replies_yields_empty_lists() {
    final RemediationPlan plan =
        new RemediationPlan(Symptom.CONNECTION_REFUSED, List.of(), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(), plan);
    final Map<String, Object> raw = new LinkedHashMap<>(report.toOutputMap());
    final Map<String, Object> planMap = new LinkedHashMap<>(asMap(raw.get("plan")));
    planMap.remove("replies");
    raw.put("plan", planMap);

    final ConsultationReport rebuilt = ConsultationReportReader.fromOutputMap(raw).orElseThrow();
    assertTrue(rebuilt.plan().replies().isEmpty());
    assertTrue(rebuilt.plan().prescriptions().isEmpty());
  }

  @Test
  void prescription_without_payload_yields_empty_map() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    final Map<String, Object> planMap = new LinkedHashMap<>(asMap(raw.get("plan")));
    final List<?> replies = (List<?>) planMap.get("replies");
    final Map<String, Object> reply = new LinkedHashMap<>(asMap(replies.get(0)));
    final Map<String, Object> presc = new LinkedHashMap<>(asMap(reply.get("prescription")));
    presc.remove("payload");
    reply.put("prescription", presc);
    planMap.put("replies", List.of(reply));
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
  void unparseable_prescription_programRef_is_dropped_but_reply_keeps_its_assessment() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    final Map<String, Object> planMap = new LinkedHashMap<>(asMap(raw.get("plan")));
    final List<?> replies = (List<?>) planMap.get("replies");
    final Map<String, Object> reply = new LinkedHashMap<>(asMap(replies.get(0)));
    reply.put(
        "prescription",
        Map.of("programRef", "no-such-program", "payload", Map.of(), "humanHint", "ignored"));
    planMap.put("replies", List.of(reply));
    raw.put("plan", planMap);

    final RemediationPlan rebuilt =
        ConsultationReportReader.fromOutputMap(raw).orElseThrow().plan();
    // The malformed prescription drops, but the reply survives — its assessment (the "why") stands.
    assertTrue(rebuilt.prescriptions().isEmpty());
    assertEquals(1, rebuilt.replies().size());
    assertFalse(rebuilt.replies().get(0).hasPrescription());
  }

  @Test
  void reply_without_a_parseable_assessment_is_dropped() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    final Map<String, Object> planMap = new LinkedHashMap<>(asMap(raw.get("plan")));
    final List<?> replies = (List<?>) planMap.get("replies");
    final Map<String, Object> reply = new LinkedHashMap<>(asMap(replies.get(0)));
    reply.remove("assessment");
    planMap.put("replies", List.of(reply));
    raw.put("plan", planMap);

    // A reply with no "why" is not a reply — it is dropped, leaving an empty (but valid) plan.
    final RemediationPlan rebuilt =
        ConsultationReportReader.fromOutputMap(raw).orElseThrow().plan();
    assertTrue(rebuilt.replies().isEmpty());
  }

  @Test
  void round_trips_a_declined_reply_preserving_the_why() {
    final Assessment assessment =
        Assessment.of(
            SchemaRef.of("dbus-tcp/declined/v1"),
            Map.of(),
            "not a dbus-TCP symptom — the systemd adapter has no treatment for timeout");
    final ReferralReply reply = ReferralReply.reconstructed(assessment, Optional.empty());
    final RemediationPlan plan =
        new RemediationPlan(Symptom.CONNECTION_REFUSED, List.of(reply), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(), plan);

    final ConsultationReport rebuilt =
        ConsultationReportReader.fromOutputMap(report.toOutputMap()).orElseThrow();

    assertEquals(1, rebuilt.plan().replies().size());
    final ReferralReply rebuiltReply = rebuilt.plan().replies().get(0);
    assertFalse(rebuiltReply.hasPrescription());
    assertEquals("dbus-tcp/declined/v1", rebuiltReply.assessment().schemaRef().id());
    assertEquals(
        "not a dbus-TCP symptom — the systemd adapter has no treatment for timeout",
        rebuiltReply.assessment().summary());
  }

  @Test
  void round_trips_a_prescribing_reply_preserving_its_assessment() {
    final String dbusUnit = SystemdUnitId.DBUS_TCP_SYSTEM_BUS.serviceUnitName();
    final Assessment assessment =
        Assessment.of(
            SchemaRef.of("dbus-tcp/connection-refused/v1"),
            Map.of("unit", dbusUnit),
            "dbus-TCP endpoint refused the connection");
    final Prescription prescription =
        Prescription.of(
            RemediationProgramRef.RESTART_UNIT,
            Map.of("unit", dbusUnit),
            "incus exec master -- systemctl restart " + dbusUnit);
    final ReferralReply reply = ReferralReply.reconstructed(assessment, Optional.of(prescription));
    final RemediationPlan plan =
        new RemediationPlan(Symptom.CONNECTION_REFUSED, List.of(reply), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(), plan);

    final ConsultationReport rebuilt =
        ConsultationReportReader.fromOutputMap(report.toOutputMap()).orElseThrow();

    assertEquals(1, rebuilt.plan().replies().size());
    final ReferralReply rebuiltReply = rebuilt.plan().replies().get(0);
    assertTrue(rebuiltReply.hasPrescription());
    assertEquals(
        RemediationProgramRef.RESTART_UNIT, rebuiltReply.prescription().get().programRef());
    assertEquals("dbus-tcp/connection-refused/v1", rebuiltReply.assessment().schemaRef().id());
    assertEquals("dbus-TCP endpoint refused the connection", rebuiltReply.assessment().summary());
  }

  @Test
  void unknown_reply_key_survives_reconstruction() {
    final Map<String, Object> raw = new LinkedHashMap<>(sampleReport().toOutputMap());
    final Map<String, Object> planMap = new LinkedHashMap<>(asMap(raw.get("plan")));
    final List<?> replies = (List<?>) planMap.get("replies");
    final Map<String, Object> reply = new LinkedHashMap<>(asMap(replies.get(0)));
    reply.put("correspondence", Map.of("seenBy", "future"));
    planMap.put("replies", List.of(reply));
    raw.put("plan", planMap);

    final Optional<ConsultationReport> rebuilt = ConsultationReportReader.fromOutputMap(raw);
    assertTrue(rebuilt.isPresent());
    assertEquals(1, rebuilt.get().plan().replies().size());
    assertEquals("dbus refused", rebuilt.get().observations().get(0).summary());
  }

  /** The output map is a {@code Map<String, Object>} by construction; the nested reads are too. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }
}
