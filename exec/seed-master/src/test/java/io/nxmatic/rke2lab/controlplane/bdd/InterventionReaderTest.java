package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.Checkpoint;
import io.nxmatic.rke2lab.doctor.Intervention;
import io.nxmatic.rke2lab.doctor.InterventionReader;
import io.nxmatic.rke2lab.doctor.ProblemRef;
import io.nxmatic.rke2lab.doctor.Provenance;
import io.nxmatic.rke2lab.doctor.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.Symptom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InterventionReaderTest {

  @Test
  void round_trip_intervention_without_prescription_or_details() {
    final Intervention original =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            Instant.parse("2026-06-13T10:13:50Z"),
            "nft delete ...",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of());

    final Optional<Intervention> reconstructed =
        InterventionReader.fromOutputMap(original.toOutputMap());

    assertTrue(reconstructed.isPresent());
    assertEquals(original, reconstructed.get());
  }

  @Test
  void round_trip_intervention_with_prescription_and_details() {
    final Intervention original =
        new Intervention(
            Provenance.PULUMI_ENGINE,
            Instant.parse("2026-06-13T10:13:50Z"),
            "systemctl restart ...",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.TIMEOUT),
            Optional.of(RemediationProgramRef.RESTART_UNIT),
            Map.of("windowFrom", "t1", "unitName", "rke2-server"));

    final Optional<Intervention> reconstructed =
        InterventionReader.fromOutputMap(original.toOutputMap());

    assertTrue(reconstructed.isPresent());
    assertEquals(original, reconstructed.get());
  }

  @Test
  void fromOutputMap_of_null_is_empty() {
    assertTrue(InterventionReader.fromOutputMap(null).isEmpty());
  }

  @Test
  void fromOutputMap_of_non_map_is_empty() {
    assertTrue(InterventionReader.fromOutputMap("a string").isEmpty());
  }

  @Test
  void fromOutputMap_without_provenance_is_empty() {
    final Map<String, Object> map = Map.of("when", "2026-06-13T10:13:50Z", "what", "something");
    assertTrue(InterventionReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void fromOutputMap_with_unparseable_provenance_is_empty() {
    final Map<String, Object> map =
        Map.of("provenance", "not-a-real-provenance", "when", "2026-06-13T10:13:50Z");
    assertTrue(InterventionReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void fromOutputMap_without_when_is_empty() {
    final Map<String, Object> map = Map.of("provenance", "operator-manual", "what", "something");
    assertTrue(InterventionReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void fromOutputMap_with_unparseable_when_is_empty() {
    final Map<String, Object> map =
        Map.of("provenance", "operator-manual", "when", "not-an-instant");
    assertTrue(InterventionReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void unknown_key_survives_into_details() {
    final Intervention original =
        new Intervention(
            Provenance.OPERATOR_MANUAL,
            Instant.parse("2026-06-13T10:13:50Z"),
            "nft delete ...",
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            Optional.empty(),
            Map.of("futureField", "tomorrow-value"));

    final Optional<Intervention> reconstructed =
        InterventionReader.fromOutputMap(original.toOutputMap());

    assertTrue(reconstructed.isPresent());
    final Intervention intervention = reconstructed.get();
    assertEquals("tomorrow-value", intervention.details().get("futureField"));
    assertFalse(intervention.details().containsKey("provenance"));
    assertFalse(intervention.details().containsKey("when"));
    assertFalse(intervention.details().containsKey("what"));
    assertFalse(intervention.details().containsKey("problem"));
    assertFalse(intervention.details().containsKey("prescriptionRef"));
  }

  @Test
  void absent_prescriptionRef_key_yields_empty_optional() {
    final Map<String, Object> map =
        Map.of(
            "provenance", "operator-manual",
            "when", "2026-06-13T10:13:50Z",
            "what", "foo",
            "problem", "systemd-adapter/connection-refused");

    final Optional<Intervention> reconstructed = InterventionReader.fromOutputMap(map);

    assertTrue(reconstructed.isPresent());
    assertTrue(reconstructed.get().prescriptionRef().isEmpty());
  }

  @Test
  void unparseable_prescriptionRef_yields_empty_optional() {
    final Map<String, Object> map =
        Map.of(
            "provenance", "operator-manual",
            "when", "2026-06-13T10:13:50Z",
            "what", "foo",
            "problem", "systemd-adapter/connection-refused",
            "prescriptionRef", "not-a-valid-ref");

    final Optional<Intervention> reconstructed = InterventionReader.fromOutputMap(map);

    assertTrue(reconstructed.isPresent());
    assertTrue(reconstructed.get().prescriptionRef().isEmpty());
  }

  @Test
  void fromOutputMap_without_problem_is_empty() {
    final Map<String, Object> map =
        Map.of(
            "provenance", "operator-manual", "when", "2026-06-13T10:13:50Z", "what", "something");
    assertTrue(InterventionReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void fromOutputMap_with_unparseable_problem_is_empty() {
    final Map<String, Object> map =
        Map.of(
            "provenance", "operator-manual",
            "when", "2026-06-13T10:13:50Z",
            "what", "something",
            "problem", "not-a-checkpoint/x");
    assertTrue(InterventionReader.fromOutputMap(map).isEmpty());
  }
}
