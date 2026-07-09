package io.nxmatic.rke2lab.doctor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.Assessment;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.SchemaRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.seed.broker.codec.DocumentCodec;
import io.nxmatic.rke2lab.seed.broker.port.Checkpoint;
import io.nxmatic.rke2lab.systemd.port.SystemdUnitId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@link DocumentCodec} round-trip of a {@link ConsultationReport} through its opaque {@code
 * Map} blob (the shape a Document's open slot carries) — the direct-decode path that replaced the
 * hand-rolled {@code ConsultationReportReader}. Proves the record graph (observations, plan,
 * replies, assessment, prescription) survives {@code toMap → fromMap} with kebab-cased enum ids,
 * that additive keys are tolerated ({@code FAIL_ON_UNKNOWN_PROPERTIES} off), and that a
 * structurally-invalid blob throws (the compact-ctor guard) so the {@code MedicalRecordReader}
 * boundary can degrade it.
 */
class ConsultationReportCodecTest {

  private static final DocumentCodec CODEC = new DocumentCodec();

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
            List.of(
                ReferralReply.reconstructed(
                    Assessment.of(SchemaRef.of("test/why/v1"), Map.of(), "test reasoning"),
                    Optional.of(prescription))),
            "adapter unreachable");
    return new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(observation), plan);
  }

  private static ConsultationReport roundTrip(ConsultationReport report) {
    return CODEC.fromMap(CODEC.toMap(report), ConsultationReport.class);
  }

  @Test
  void round_trips_a_full_report() {
    final ConsultationReport rebuilt = roundTrip(sampleReport());

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
  void missing_checkpointId_is_rejected() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sampleReport()));
    raw.remove("checkpointId");
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.fromMap(raw, ConsultationReport.class));
  }

  @Test
  void missing_plan_is_rejected() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sampleReport()));
    raw.remove("plan");
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.fromMap(raw, ConsultationReport.class));
  }

  @Test
  void unparseable_plan_symptom_is_rejected() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sampleReport()));
    final Map<String, Object> plan = new LinkedHashMap<>(asMap(raw.get("plan")));
    plan.put("symptom", "not-a-real-symptom");
    raw.put("plan", plan);
    // A lenient Symptom creator yields null → RemediationPlan's guard throws; the enclosing entry
    // degrades at the MedicalRecordReader boundary.
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.fromMap(raw, ConsultationReport.class));
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

    final Observation rebuilt = roundTrip(report).observations().get(0);

    assertEquals("tomorrow", rebuilt.details().get("futureField"));
    assertEquals("endpoint-gate", rebuilt.details().get("source"));
    // canonical keys must NOT leak into details
    assertFalse(rebuilt.details().containsKey("status"));
    assertFalse(rebuilt.details().containsKey("summary"));
    assertFalse(rebuilt.details().containsKey(Symptom.ENVELOPE_KEY));
  }

  @Test
  void extra_top_level_key_does_not_break_reconstruction() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sampleReport()));
    raw.put("correspondence", Map.of("seenBy", "future-doctor"));
    assertEquals("systemd-adapter", CODEC.fromMap(raw, ConsultationReport.class).checkpointId());
  }

  @Test
  void missing_observations_yields_empty_list() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sampleReport()));
    raw.remove("observations");
    assertTrue(CODEC.fromMap(raw, ConsultationReport.class).observations().isEmpty());
  }

  @Test
  void missing_replies_yields_empty_lists() {
    final RemediationPlan plan =
        new RemediationPlan(Symptom.CONNECTION_REFUSED, List.of(), "adapter unreachable");
    final ConsultationReport report =
        new ConsultationReport(Checkpoint.SYSTEMD_ADAPTER.slug(), List.of(), plan);
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(report));
    final Map<String, Object> planMap = new LinkedHashMap<>(asMap(raw.get("plan")));
    planMap.remove("replies");
    raw.put("plan", planMap);

    final ConsultationReport rebuilt = CODEC.fromMap(raw, ConsultationReport.class);
    assertTrue(rebuilt.plan().replies().isEmpty());
    assertTrue(rebuilt.plan().prescriptions().isEmpty());
  }

  @Test
  void prescription_without_payload_yields_empty_map() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sampleReport()));
    final Map<String, Object> planMap = new LinkedHashMap<>(asMap(raw.get("plan")));
    final List<?> replies = (List<?>) planMap.get("replies");
    final Map<String, Object> reply = new LinkedHashMap<>(asMap(replies.get(0)));
    final Map<String, Object> presc = new LinkedHashMap<>(asMap(reply.get("prescription")));
    presc.remove("payload");
    reply.put("prescription", presc);
    planMap.put("replies", List.of(reply));
    raw.put("plan", planMap);

    final Prescription withoutPayload =
        CODEC.fromMap(raw, ConsultationReport.class).plan().primaryPrescription().orElseThrow();
    assertTrue(withoutPayload.payload().isEmpty());
    assertEquals(RemediationProgramRef.RESTART_UNIT, withoutPayload.programRef());
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

    final ConsultationReport rebuilt = roundTrip(report);

    assertEquals(1, rebuilt.plan().replies().size());
    final ReferralReply rebuiltReply = rebuilt.plan().replies().get(0);
    assertFalse(rebuiltReply.hasPrescription());
    assertEquals("dbus-tcp/declined/v1", rebuiltReply.assessment().schemaRef().id());
    assertEquals(
        "not a dbus-TCP symptom — the systemd adapter has no treatment for timeout",
        rebuiltReply.assessment().summary());
    // The transient referral back-ref is never serialized.
    assertTrue(rebuiltReply.referral().isEmpty());
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

    final ConsultationReport rebuilt = roundTrip(report);

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
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sampleReport()));
    final Map<String, Object> planMap = new LinkedHashMap<>(asMap(raw.get("plan")));
    final List<?> replies = (List<?>) planMap.get("replies");
    final Map<String, Object> reply = new LinkedHashMap<>(asMap(replies.get(0)));
    reply.put("correspondence", Map.of("seenBy", "future"));
    planMap.put("replies", List.of(reply));
    raw.put("plan", planMap);

    final ConsultationReport rebuilt = CODEC.fromMap(raw, ConsultationReport.class);
    assertEquals(1, rebuilt.plan().replies().size());
    assertEquals("dbus refused", rebuilt.observations().get(0).summary());
  }

  /** The output map is a {@code Map<String, Object>} by construction; the nested reads are too. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }
}
