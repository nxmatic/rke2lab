package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InterventionTest {

  @Test
  void operator_manual_without_prescription_ref() {
    final Intervention intervention =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            Instant.parse("2026-06-13T10:13:50Z"),
            "nft delete table inet rke2lab_probe_block",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    final Map<String, Object> outputMap = intervention.toOutputMap();

    assertEquals("operator-manual", outputMap.get("provenance"));
    assertEquals("2026-06-13T10:13:50Z", outputMap.get("when"));
    assertEquals("nft delete table inet rke2lab_probe_block", outputMap.get("what"));
    assertEquals("systemd-adapter/connection-refused", outputMap.get("problem"));
    assertFalse(outputMap.containsKey("prescriptionRef"));
  }

  @Test
  void pulumi_engine_with_prescription_ref_and_details() {
    final Intervention intervention =
        new Intervention(
            Provenance.PULUMI_ENGINE,
            Instant.parse("2026-06-13T11:00:00Z"),
            "restart systemd unit",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.of(RemediationProgramRef.RESTART_UNIT),
            Map.of("windowFrom", "t1", "unitName", "dbus.service"));

    final Map<String, Object> outputMap = intervention.toOutputMap();

    assertEquals("pulumi-engine", outputMap.get("provenance"));
    assertEquals("2026-06-13T11:00:00Z", outputMap.get("when"));
    assertEquals("restart systemd unit", outputMap.get("what"));
    assertEquals("systemd-adapter/connection-refused", outputMap.get("problem"));
    assertEquals("restart-systemd-unit", outputMap.get("prescriptionRef"));
    assertEquals("t1", outputMap.get("windowFrom"));
    assertEquals("dbus.service", outputMap.get("unitName"));
  }

  @Test
  void null_normalization_in_canonical_constructor() {
    final Intervention intervention =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            Instant.parse("2026-06-13T12:00:00Z"),
            "x",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER),
            null,
            null);

    final Map<String, Object> outputMap = intervention.toOutputMap();

    assertEquals("operator-manual", outputMap.get("provenance"));
    assertEquals("2026-06-13T12:00:00Z", outputMap.get("when"));
    assertEquals("x", outputMap.get("what"));
    assertEquals("systemd-adapter", outputMap.get("problem"));
    assertFalse(outputMap.containsKey("prescriptionRef"));
    // No extra detail keys beyond the core 4
    assertTrue(outputMap.size() == 4); // provenance, when, what, problem
  }

  @Test
  void checkpoint_only_problem_ref_renders_without_symptom() {
    final Intervention intervention =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            Instant.parse("2026-06-14T09:30:00Z"),
            "nft delete ...",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER),
            Optional.empty(),
            Map.of());

    final Map<String, Object> outputMap = intervention.toOutputMap();

    assertEquals("systemd-adapter", outputMap.get("problem"));
  }
}
