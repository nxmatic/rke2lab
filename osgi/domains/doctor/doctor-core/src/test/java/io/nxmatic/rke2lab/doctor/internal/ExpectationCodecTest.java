package io.nxmatic.rke2lab.doctor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.ResolutionPredicate;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.seed.broker.codec.DocumentCodec;
import io.nxmatic.rke2lab.seed.broker.port.Checkpoint;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@link DocumentCodec} round-trip of an {@link Expectation} through its opaque {@code Map}
 * blob — the direct-decode path that replaced the hand-rolled {@code ExpectationReader}. Each
 * required field decodes via its annotated value type ({@link ProblemRef} / {@link
 * RemediationProgramRef} strings, the polymorphic predicate, the ISO-8601 {@code recordedAt}); a
 * missing or unparseable required field makes the compact-ctor guard throw, so the {@code
 * MedicalRecordReader} boundary degrades the entry rather than folding a half-null record.
 */
class ExpectationCodecTest {

  private static final DocumentCodec CODEC = new DocumentCodec();

  private static Expectation sample() {
    return new Expectation(
        ProblemRef.of(Checkpoint.SYSTEMD_ADAPTER, Symptom.CONNECTION_REFUSED),
        RemediationProgramRef.RESTART_UNIT,
        new ResolutionPredicate(Symptom.CONNECTION_REFUSED),
        Instant.parse("2026-06-13T10:00:00Z"));
  }

  @Test
  void round_trip_preserves_all_fields() {
    final Expectation original = sample();

    final Expectation rebuilt = CODEC.fromMap(CODEC.toMap(original), Expectation.class);

    assertEquals(original, rebuilt);
  }

  @Test
  void serializes_fields_to_their_wire_form() {
    final Map<String, Object> map = CODEC.toMap(sample());

    assertEquals("systemd-adapter/connection-refused", map.get("problem"));
    assertEquals("restart-systemd-unit", map.get("fromPrescription"));
    assertEquals("2026-06-13T10:00:00Z", map.get("recordedAt"));

    @SuppressWarnings("unchecked")
    final Map<String, Object> predicateMap = (Map<String, Object>) map.get("predicate");
    assertEquals("resolution", predicateMap.get("kind"));
    assertEquals("connection-refused", predicateMap.get("symptom"));
  }

  @Test
  void unparseable_problem_is_rejected() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sample()));
    raw.put("problem", "not-a-valid-problem");
    assertThrows(IllegalArgumentException.class, () -> CODEC.fromMap(raw, Expectation.class));
  }

  @Test
  void missing_problem_is_rejected() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sample()));
    raw.remove("problem");
    assertThrows(IllegalArgumentException.class, () -> CODEC.fromMap(raw, Expectation.class));
  }

  @Test
  void missing_from_prescription_is_rejected() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sample()));
    raw.remove("fromPrescription");
    assertThrows(IllegalArgumentException.class, () -> CODEC.fromMap(raw, Expectation.class));
  }

  @Test
  void missing_predicate_is_rejected() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sample()));
    raw.remove("predicate");
    assertThrows(IllegalArgumentException.class, () -> CODEC.fromMap(raw, Expectation.class));
  }

  @Test
  void missing_recorded_at_is_rejected() {
    final Map<String, Object> raw = new LinkedHashMap<>(CODEC.toMap(sample()));
    raw.remove("recordedAt");
    assertThrows(IllegalArgumentException.class, () -> CODEC.fromMap(raw, Expectation.class));
  }

  @Test
  void symptom_accessor_returns_the_problems_symptom() {
    assertEquals(Symptom.CONNECTION_REFUSED, sample().symptom());
  }
}
