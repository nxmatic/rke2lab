package io.nxmatic.rke2lab.doctor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.InterventionWire;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InterventionReaderTest {

  private static final Instant WHEN = Instant.parse("2026-06-13T10:13:50Z");

  @Test
  void round_trip_intervention_without_prescription_or_details() {
    final InterventionWire wire =
        new InterventionWire(
            "operator-manual",
            WHEN,
            "nft delete ...",
            "systemd-adapter/connection-refused",
            Optional.empty(),
            Map.of());

    final Intervention expected =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            WHEN,
            "nft delete ...",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    assertEquals(Optional.of(expected), InterventionReader.fromWire(wire));
  }

  @Test
  void round_trip_intervention_with_prescription_and_details() {
    final InterventionWire wire =
        new InterventionWire(
            "pulumi-engine",
            WHEN,
            "systemctl restart ...",
            "systemd-adapter/timeout",
            Optional.of("restart-systemd-unit"),
            Map.of("windowFrom", "t1", "unitName", "rke2-server"));

    final Intervention expected =
        new Intervention(
            Provenance.PULUMI_ENGINE,
            WHEN,
            "systemctl restart ...",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.TIMEOUT),
            Optional.of(RemediationProgramRef.RESTART_UNIT),
            Map.of("windowFrom", "t1", "unitName", "rke2-server"));

    assertEquals(Optional.of(expected), InterventionReader.fromWire(wire));
  }

  @Test
  void fromWire_of_null_is_empty() {
    assertTrue(InterventionReader.fromWire(null).isEmpty());
  }

  @Test
  void unparseable_provenance_is_empty() {
    final InterventionWire wire =
        new InterventionWire(
            "not-a-real-provenance",
            WHEN,
            "something",
            "systemd-adapter/connection-refused",
            Optional.empty(),
            Map.of());
    assertTrue(InterventionReader.fromWire(wire).isEmpty());
  }

  @Test
  void unparseable_problem_is_empty() {
    final InterventionWire wire =
        new InterventionWire(
            "operator-manual", WHEN, "something", "not-a-checkpoint/x", Optional.empty(), Map.of());
    assertTrue(InterventionReader.fromWire(wire).isEmpty());
  }

  @Test
  void details_are_carried_through() {
    final InterventionWire wire =
        new InterventionWire(
            "operator-manual",
            WHEN,
            "nft delete ...",
            "systemd-adapter/connection-refused",
            Optional.empty(),
            Map.of("futureField", "tomorrow-value"));

    final Intervention intervention = InterventionReader.fromWire(wire).orElseThrow();
    assertEquals("tomorrow-value", intervention.details().get("futureField"));
  }

  @Test
  void absent_prescriptionRef_yields_empty_optional() {
    final InterventionWire wire =
        new InterventionWire(
            "operator-manual",
            WHEN,
            "foo",
            "systemd-adapter/connection-refused",
            Optional.empty(),
            Map.of());
    assertTrue(InterventionReader.fromWire(wire).orElseThrow().prescriptionRef().isEmpty());
  }

  @Test
  void unparseable_prescriptionRef_yields_empty_optional() {
    final InterventionWire wire =
        new InterventionWire(
            "operator-manual",
            WHEN,
            "foo",
            "systemd-adapter/connection-refused",
            Optional.of("not-a-valid-ref"),
            Map.of());
    assertTrue(InterventionReader.fromWire(wire).orElseThrow().prescriptionRef().isEmpty());
  }
}
