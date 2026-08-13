package io.seedmatic.rke2lab.doctor.contract;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AssessmentTest {

  @Test
  void null_summary_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Assessment.of(SchemaRef.of("x/y/v1"), Map.of(), null));

    assertThrows(
        IllegalArgumentException.class,
        () -> Assessment.of(SchemaRef.of("x/y/v1"), Map.of(), "  "));
  }

  @Test
  void null_payload_defaults_empty() {
    final Assessment assessment = Assessment.of(SchemaRef.of("x/y/v1"), null, "why");
    assertTrue(assessment.payload().isEmpty());
  }
}
