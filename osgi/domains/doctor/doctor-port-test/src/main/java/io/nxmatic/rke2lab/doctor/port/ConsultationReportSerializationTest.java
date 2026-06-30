package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import io.nxmatic.rke2lab.systemd.port.SystemdUnitId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the flat, string-keyed serialization of the diagnostic layer (symptom / observations / plan)
 * — the form a checkpoint resource registers as an additive Pulumi output. Ids are kebab ({@link
 * Symptom#id()} / {@link RemediationProgramRef#id()}), never enum {@code name()}.
 */
class ConsultationReportSerializationTest {

  @Test
  void report_serializes_to_a_flat_kebab_keyed_map() {
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
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(observation), plan);

    final Map<String, Object> map = report.toOutputMap();

    assertEquals("systemd-adapter", map.get("checkpointId"));

    final Map<?, ?> planMap = (Map<?, ?>) map.get("plan");
    assertEquals(Symptom.CONNECTION_REFUSED.id(), planMap.get("symptom"));

    // The plan now serializes its replies; each reply nests its assessment (always) and its
    // prescription (when present) — the kebab programRef id lives under replies[].prescription.
    final Map<?, ?> firstReply = (Map<?, ?>) ((List<?>) planMap.get("replies")).get(0);
    final Map<?, ?> prescriptionMap = (Map<?, ?>) firstReply.get("prescription");
    assertEquals("restart-systemd-unit", prescriptionMap.get("programRef"));

    assertInstanceOf(List.class, map.get("observations"));
  }

  @Test
  void plan_serializes_replies_with_assessment() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
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
    final ReferralReply reply =
        ReferralReply.reconstructed(assessment, java.util.Optional.of(prescription));
    final RemediationPlan plan =
        new RemediationPlan(Symptom.CONNECTION_REFUSED, List.of(reply), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(observation), plan);

    final Map<String, Object> map = report.toOutputMap();
    final Map<?, ?> planMap = (Map<?, ?>) map.get("plan");
    final List<?> replies = (List<?>) planMap.get("replies");
    final Map<?, ?> firstReply = (Map<?, ?>) replies.get(0);

    assertTrue(firstReply.containsKey("assessment"));
    final Map<?, ?> assessmentMap = (Map<?, ?>) firstReply.get("assessment");
    assertEquals("dbus-tcp/connection-refused/v1", assessmentMap.get("schemaRef"));
    assertEquals("dbus-TCP endpoint refused the connection", assessmentMap.get("summary"));

    assertTrue(firstReply.containsKey("prescription"));
    final Map<?, ?> prescriptionMap = (Map<?, ?>) firstReply.get("prescription");
    assertEquals("restart-systemd-unit", prescriptionMap.get("programRef"));

    assertFalse(planMap.containsKey("prescriptions"));
  }

  @Test
  void declined_reply_serializes_assessment_without_prescription() {
    final Observation observation =
        Observation.failed(
            Symptom.CONNECTION_REFUSED, "dbus refused", Map.of("source", "endpoint-gate"));
    final Assessment assessment =
        Assessment.of(
            SchemaRef.of("network/reachability/v1"),
            Map.of("symptom", "connection-refused"),
            "endpoint unreachable at the TCP layer; no network-level remediation");
    final ReferralReply reply = ReferralReply.reconstructed(assessment, java.util.Optional.empty());
    final RemediationPlan plan =
        new RemediationPlan(Symptom.CONNECTION_REFUSED, List.of(reply), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(observation), plan);

    final Map<String, Object> map = report.toOutputMap();
    final Map<?, ?> planMap = (Map<?, ?>) map.get("plan");
    final List<?> replies = (List<?>) planMap.get("replies");
    final Map<?, ?> firstReply = (Map<?, ?>) replies.get(0);

    assertTrue(firstReply.containsKey("assessment"));
    final Map<?, ?> assessmentMap = (Map<?, ?>) firstReply.get("assessment");
    assertEquals("network/reachability/v1", assessmentMap.get("schemaRef"));

    assertFalse(firstReply.containsKey("prescription"));
  }
}
