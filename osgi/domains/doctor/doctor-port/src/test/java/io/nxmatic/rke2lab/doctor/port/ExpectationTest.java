package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.doctor.records.*;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExpectationTest {

  @Test
  void to_output_map_serializes_all_fields() {
    final Expectation expectation =
        new Expectation(
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            RemediationProgramRef.RESTART_UNIT,
            new ResolutionPredicate(Symptom.CONNECTION_REFUSED),
            Instant.parse("2026-06-13T10:00:00Z"));

    final Map<String, Object> map = expectation.toOutputMap();

    assertEquals("systemd-adapter/connection-refused", map.get("problem"));
    assertFalse(map.containsKey("symptom"));
    assertEquals("restart-systemd-unit", map.get("fromPrescription"));
    assertEquals("2026-06-13T10:00:00Z", map.get("recordedAt"));

    // predicate is a nested map
    @SuppressWarnings("unchecked")
    final Map<String, Object> predicateMap = (Map<String, Object>) map.get("predicate");
    assertNotNull(predicateMap);
    assertEquals("resolution", predicateMap.get("kind"));
    assertEquals("connection-refused", predicateMap.get("symptom"));
  }

  @Test
  void round_trip_via_reader_succeeds() {
    final Expectation original =
        new Expectation(
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            RemediationProgramRef.RESTART_UNIT,
            new ResolutionPredicate(Symptom.CONNECTION_REFUSED),
            Instant.parse("2026-06-13T10:00:00Z"));

    final Optional<Expectation> reconstructed =
        ExpectationReader.fromOutputMap(original.toOutputMap());

    assertTrue(reconstructed.isPresent());
    assertEquals(original, reconstructed.get());
  }

  @Test
  void from_output_map_returns_empty_on_null() {
    assertTrue(ExpectationReader.fromOutputMap(null).isEmpty());
  }

  @Test
  void from_output_map_returns_empty_on_non_map() {
    assertTrue(ExpectationReader.fromOutputMap("not a map").isEmpty());
  }

  @Test
  void from_output_map_returns_empty_when_problem_missing() {
    final Map<String, Object> map =
        Map.of(
            "fromPrescription",
            "restart-systemd-unit",
            "predicate",
            Map.of("kind", "resolution", "symptom", "connection-refused"),
            "recordedAt",
            "2026-06-13T10:00:00Z");

    assertTrue(ExpectationReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void from_output_map_returns_empty_when_problem_unparseable() {
    final Map<String, Object> map =
        Map.of(
            "problem",
            "not-a-valid-problem",
            "fromPrescription",
            "restart-systemd-unit",
            "predicate",
            Map.of("kind", "resolution", "symptom", "connection-refused"),
            "recordedAt",
            "2026-06-13T10:00:00Z");

    assertTrue(ExpectationReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void from_output_map_returns_empty_when_from_prescription_missing() {
    final Map<String, Object> map =
        Map.of(
            "problem",
            "systemd-adapter/connection-refused",
            "predicate",
            Map.of("kind", "resolution", "symptom", "connection-refused"),
            "recordedAt",
            "2026-06-13T10:00:00Z");

    assertTrue(ExpectationReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void from_output_map_returns_empty_when_predicate_missing() {
    final Map<String, Object> map =
        Map.of(
            "problem",
            "systemd-adapter/connection-refused",
            "fromPrescription",
            "restart-systemd-unit",
            "recordedAt",
            "2026-06-13T10:00:00Z");

    assertTrue(ExpectationReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void from_output_map_returns_empty_when_recorded_at_missing() {
    final Map<String, Object> map =
        Map.of(
            "problem",
            "systemd-adapter/connection-refused",
            "fromPrescription",
            "restart-systemd-unit",
            "predicate",
            Map.of("kind", "resolution", "symptom", "connection-refused"));

    assertTrue(ExpectationReader.fromOutputMap(map).isEmpty());
  }

  @Test
  void from_output_map_returns_empty_when_recorded_at_unparseable() {
    final Map<String, Object> map =
        Map.of(
            "problem",
            "systemd-adapter/connection-refused",
            "fromPrescription",
            "restart-systemd-unit",
            "predicate",
            Map.of("kind", "resolution", "symptom", "connection-refused"),
            "recordedAt",
            "not-an-instant");

    assertFalse(ExpectationReader.fromOutputMap(map).isPresent());
  }

  @Test
  void symptom_accessor_returns_the_problems_symptom() {
    final Expectation expectation =
        new Expectation(
            ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
            RemediationProgramRef.RESTART_UNIT,
            new ResolutionPredicate(Symptom.CONNECTION_REFUSED),
            Instant.parse("2026-06-13T10:00:00Z"));

    assertEquals(Symptom.CONNECTION_REFUSED, expectation.symptom());
  }
}
